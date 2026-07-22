package org.kseco;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Vault 经济对接 — 100% 反射调用，零编译依赖。
 * 运行时由服务器上的 Vault + 经济插件（EssentialsX/CMI 等）提供，
 * 任意版本均可，无需重编译 ks-Eco。
 */
public final class VaultHook {
    private static final double MAX_TRANSACTION_AMOUNT = 1_000_000_000_000d;

    private final JavaPlugin plugin;
    private Object economy;          // net.milkbowl.vault.economy.Economy 实例
    private boolean available;

    // 反射缓存
    private Method getBalanceMethod;
    private Method hasMethod;
    private Method withdrawMethod;
    private Method depositMethod;
    private Method formatMethod;
    private Method currencyNamePluralMethod;
    private Method getNameMethod;

    public VaultHook(JavaPlugin plugin) {
        this.plugin = plugin;
        refresh();
    }

    /** 重新检测 Vault 经济提供者（可在 BuiltinEconomy 注册后调用） */
    public void refresh() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return;
        }
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(economyClass);
            if (rsp == null) {
                return;
            }
            this.economy = rsp.getProvider();
            if (this.economy == null) return;

            this.getBalanceMethod = economyClass.getMethod("getBalance", OfflinePlayer.class);
            this.hasMethod = economyClass.getMethod("has", OfflinePlayer.class, double.class);
            this.withdrawMethod = economyClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            this.depositMethod = economyClass.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            this.formatMethod = economyClass.getMethod("format", double.class);
            this.currencyNamePluralMethod = economyClass.getMethod("currencyNamePlural");
            this.getNameMethod = economyClass.getMethod("getName");

            this.available = true;
            plugin.getLogger().info("Vault 已对接: " + getName());
        } catch (Exception e) {
            this.available = false;
        }
    }

    private void setup() {
        refresh();
    }

    public boolean isAvailable() {
        return walletAvailable(available && economy != null, directBuiltin() != null);
    }

    // ---- 便捷方法（全部反射调用） ----

    public double getBalance(OfflinePlayer player) {
        BuiltinEconomy direct = directBuiltin();
        if (!available && direct != null) return direct.getBalance(player.getUniqueId());
        if (!available) return 0.0;
        try {
            return (double) getBalanceMethod.invoke(economy, player);
        } catch (Exception e) {
            return 0.0;
        }
    }

    public boolean has(OfflinePlayer player, double amount) {
        if (!validAmount(amount, true)) return false;
        BuiltinEconomy direct = directBuiltin();
        if (!available && direct != null) return direct.getBalance(player.getUniqueId()) >= amount;
        if (!available) return false;
        try {
            return (boolean) hasMethod.invoke(economy, player, amount);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        if (!validAmount(amount, true)) return false;
        BuiltinEconomy direct = directBuiltin();
        if (!available && direct != null) {
            boolean success = direct.withdraw(player.getUniqueId(), player.getName(), amount);
            if (success && plugin instanceof KsEco eco) {
                eco.publishCrossServerInvalidation("balance", player.getUniqueId().toString());
            }
            return success;
        }
        if (!available) return false;
        if (amount == 0.0d) return true;
        try {
            Object response = withdrawMethod.invoke(economy, player, amount);
            // EconomyResponse.transactionSuccess()
            Method successMethod = response.getClass().getMethod("transactionSuccess");
            boolean success = (boolean) successMethod.invoke(response);
            if (success && plugin instanceof KsEco eco) {
                eco.publishCrossServerInvalidation("balance", player.getUniqueId().toString());
            }
            return success;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        if (!validAmount(amount, true)) return false;
        BuiltinEconomy direct = directBuiltin();
        if (!available && direct != null) {
            boolean success = amount == 0.0d
                    || direct.deposit(player.getUniqueId(), player.getName(), amount);
            if (success && plugin instanceof KsEco eco) {
                eco.publishCrossServerInvalidation("balance", player.getUniqueId().toString());
            }
            return success;
        }
        if (!available) return false;
        if (amount == 0.0d) return true;
        try {
            Object response = depositMethod.invoke(economy, player, amount);
            Method successMethod = response.getClass().getMethod("transactionSuccess");
            boolean ok = (boolean) successMethod.invoke(response);
            if (ok && plugin instanceof KsEco eco) {
                eco.publishCrossServerInvalidation("balance", player.getUniqueId().toString());
            }
            if (!ok) {
                Method errMethod = response.getClass().getMethod("errorMessage");
                String err = (String) errMethod.invoke(response);
                plugin.getLogger().warning("Vault deposit 失败: " + err + " (player=" + player.getUniqueId() + " amount=" + amount + ")");
            }
            return ok;
        } catch (Exception e) {
            plugin.getLogger().warning("Vault deposit 异常: " + e.getMessage());
            return false;
        }
    }

    public boolean transfer(OfflinePlayer from, OfflinePlayer to, double amount) {
        if (!isAvailable() || !validAmount(amount, false) || !has(from, amount)) return false;
        if (!withdraw(from, amount)) return false;
        if (!deposit(to, amount)) {
            deposit(from, amount); // 回滚
            return false;
        }
        return true;
    }

    private boolean validAmount(double amount, boolean allowZero) {
        return Double.isFinite(amount)
                && (allowZero ? amount >= 0.0d : amount > 0.0d)
                && amount <= MAX_TRANSACTION_AMOUNT;
    }

    public String format(double amount) {
        if (!available && directBuiltin() != null) return String.format("%.2f 金币", amount);
        if (!available) return String.format("%.2f", amount);
        try {
            return (String) formatMethod.invoke(economy, amount);
        } catch (Exception e) {
            return String.format("%.2f", amount);
        }
    }

    public String currencyName() {
        if (!available && directBuiltin() != null) return "金币";
        if (!available) return "金币";
        try {
            String name = (String) currencyNamePluralMethod.invoke(economy);
            return name != null ? name : "金币";
        } catch (Exception e) {
            return "金币";
        }
    }

    public String getName() {
        if (!available && directBuiltin() != null) return "ks-Eco内置经济";
        if (!available) return "无";
        try {
            return (String) getNameMethod.invoke(economy);
        } catch (Exception e) {
            return "未知";
        }
    }

    private BuiltinEconomy directBuiltin() {
        if (!(plugin instanceof KsEco eco) || !eco.foliaRuntime()) return null;
        BuiltinEconomy builtin = eco.builtinEconomy();
        return builtin != null && builtin.isRegistered() ? builtin : null;
    }

    static boolean walletAvailable(boolean externalVault, boolean directBuiltin) {
        return externalVault || directBuiltin;
    }

    boolean directBuiltinActive() {
        return !available && directBuiltin() != null;
    }

    boolean withdrawDirect(UUID playerUuid, String playerName, double amount) {
        BuiltinEconomy direct = directBuiltin();
        if (direct == null || !validAmount(amount, true)) return false;
        boolean success = direct.withdraw(playerUuid, playerName, amount);
        if (success && plugin instanceof KsEco eco) {
            eco.publishCrossServerInvalidation("balance", playerUuid.toString());
        }
        return success;
    }

    boolean depositDirect(UUID playerUuid, String playerName, double amount) {
        BuiltinEconomy direct = directBuiltin();
        if (direct == null || !validAmount(amount, true)) return false;
        boolean success = amount == 0.0d || direct.deposit(playerUuid, playerName, amount);
        if (success && plugin instanceof KsEco eco) {
            eco.publishCrossServerInvalidation("balance", playerUuid.toString());
        }
        return success;
    }
}
