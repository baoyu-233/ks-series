package org.kseries.compat.service;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

public final class EconomyBridge {

    private final JavaPlugin plugin;
    private Object provider;
    private boolean ksEcoProvider;
    private boolean available;
    private String providerName = "none";
    private Method getBalance;
    private Method has;
    private Method withdraw;
    private Method format;

    public EconomyBridge(JavaPlugin plugin) {
        this.plugin = plugin;
        refresh();
    }

    public void refresh() {
        clear();
        if (tryKsEco()) return;
        tryVault();
    }

    private void clear() {
        provider = null;
        ksEcoProvider = false;
        available = false;
        providerName = "none";
        getBalance = null;
        has = null;
        withdraw = null;
        format = null;
    }

    private boolean tryKsEco() {
        try {
            Plugin eco = Bukkit.getPluginManager().getPlugin("ks-Eco");
            if (eco == null || !eco.isEnabled()) return false;
            Object hook = eco.getClass().getMethod("vaultHook").invoke(eco);
            if (hook == null) return false;
            Method isAvailable = hook.getClass().getMethod("isAvailable");
            if (!(Boolean) isAvailable.invoke(hook)) return false;
            this.provider = hook;
            this.ksEcoProvider = true;
            this.getBalance = hook.getClass().getMethod("getBalance", OfflinePlayer.class);
            this.has = hook.getClass().getMethod("has", OfflinePlayer.class, double.class);
            this.withdraw = hook.getClass().getMethod("withdraw", OfflinePlayer.class, double.class);
            this.format = hook.getClass().getMethod("format", double.class);
            this.available = true;
            this.providerName = "ks-Eco";
            return true;
        } catch (Exception e) {
            plugin.getLogger().fine("ks-Eco economy bridge unavailable: " + e.getMessage());
            return false;
        }
    }

    private boolean tryVault() {
        try {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) return false;
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(economyClass);
            if (rsp == null || rsp.getProvider() == null) return false;
            this.provider = rsp.getProvider();
            this.getBalance = economyClass.getMethod("getBalance", OfflinePlayer.class);
            this.has = economyClass.getMethod("has", OfflinePlayer.class, double.class);
            this.withdraw = economyClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            this.format = economyClass.getMethod("format", double.class);
            Method name = economyClass.getMethod("getName");
            this.providerName = "Vault:" + name.invoke(provider);
            this.available = true;
            return true;
        } catch (Exception e) {
            plugin.getLogger().fine("Vault economy bridge unavailable: " + e.getMessage());
            return false;
        }
    }

    public boolean isAvailable() {
        return available && provider != null;
    }

    public String providerName() {
        return providerName;
    }

    public double getBalance(OfflinePlayer player) {
        if (!isAvailable()) return 0.0;
        try {
            return ((Number) getBalance.invoke(provider, player)).doubleValue();
        } catch (Exception e) {
            return 0.0;
        }
    }

    public boolean has(OfflinePlayer player, double amount) {
        if (!isAvailable()) return false;
        try {
            return (Boolean) has.invoke(provider, player, amount);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        if (!isAvailable()) return false;
        if (amount <= 0.0) return true;
        try {
            Object result = withdraw.invoke(provider, player, amount);
            if (ksEcoProvider) return Boolean.TRUE.equals(result);
            Method success = result.getClass().getMethod("transactionSuccess");
            return (Boolean) success.invoke(result);
        } catch (Exception e) {
            return false;
        }
    }

    public String format(double amount) {
        if (!isAvailable()) return String.format("%.2f", amount);
        try {
            return String.valueOf(format.invoke(provider, amount));
        } catch (Exception e) {
            return String.format("%.2f", amount);
        }
    }
}
