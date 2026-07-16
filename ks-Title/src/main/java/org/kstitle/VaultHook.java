package org.kstitle;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

/**
 * Vault 经济对接 — 100% 反射调用，零编译依赖（照抄 ks-Eco/VaultHook.java 模式）。
 * 运行时由服务器上的 Vault + 经济插件（ks-Eco BuiltinEconomy 等）提供。
 */
public final class VaultHook {

    private final JavaPlugin plugin;
    private Object economy;
    private boolean available;

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

    public void refresh() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return;
        }
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(economyClass);
            if (rsp == null) return;
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

    public boolean isAvailable() {
        return available && economy != null;
    }

    public double getBalance(OfflinePlayer player) {
        if (!available) return 0.0;
        try {
            return (double) getBalanceMethod.invoke(economy, player);
        } catch (Exception e) {
            return 0.0;
        }
    }

    public boolean has(OfflinePlayer player, double amount) {
        if (!available) return false;
        try {
            return (boolean) hasMethod.invoke(economy, player, amount);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        if (!available) return false;
        try {
            Object response = withdrawMethod.invoke(economy, player, amount);
            Method successMethod = response.getClass().getMethod("transactionSuccess");
            return (boolean) successMethod.invoke(response);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        if (!available) return false;
        try {
            Object response = depositMethod.invoke(economy, player, amount);
            Method successMethod = response.getClass().getMethod("transactionSuccess");
            return (boolean) successMethod.invoke(response);
        } catch (Exception e) {
            return false;
        }
    }

    public String format(double amount) {
        if (!available) return String.format("%.2f", amount);
        try {
            return (String) formatMethod.invoke(economy, amount);
        } catch (Exception e) {
            return String.format("%.2f", amount);
        }
    }

    public String currencyName() {
        if (!available) return "金币";
        try {
            String name = (String) currencyNamePluralMethod.invoke(economy);
            return name != null ? name : "金币";
        } catch (Exception e) {
            return "金币";
        }
    }

    public String getName() {
        if (!available) return "无";
        try {
            return (String) getNameMethod.invoke(economy);
        } catch (Exception e) {
            return "未知";
        }
    }
}
