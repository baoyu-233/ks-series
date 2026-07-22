package org.kseco.extra.tax;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.kseco.KsEco;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** Folia-aware boundary for external Vault and the direct JDBC wallet. */
final class TaxWalletGateway {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final KsEco eco;

    TaxWalletGateway(KsEco eco) {
        this.eco = eco;
    }

    boolean has(UUID playerUuid, String playerName, double amount) {
        return eco.vaultHook().directBuiltinActive()
                ? eco.vaultHook().hasDirect(playerUuid, amount)
                : external(playerUuid, player -> eco.vaultHook().has(player, amount), false);
    }

    double balance(UUID playerUuid, String playerName) {
        return eco.vaultHook().directBuiltinActive()
                ? eco.vaultHook().getBalanceDirect(playerUuid)
                : external(playerUuid, eco.vaultHook()::getBalance, 0.0d);
    }

    boolean withdraw(UUID playerUuid, String playerName, double amount) {
        return eco.vaultHook().directBuiltinActive()
                ? eco.vaultHook().withdrawDirect(playerUuid, safeName(playerUuid, playerName), amount)
                : external(playerUuid, player -> eco.vaultHook().withdraw(player, amount), false);
    }

    boolean deposit(UUID playerUuid, String playerName, double amount) {
        return eco.vaultHook().directBuiltinActive()
                ? eco.vaultHook().depositDirect(playerUuid, safeName(playerUuid, playerName), amount)
                : external(playerUuid, player -> eco.vaultHook().deposit(player, amount), false);
    }

    private <T> T external(UUID playerUuid, Function<OfflinePlayer, T> operation, T fallback) {
        try {
            Player online = eco.scheduler().callGlobal(() -> Bukkit.getPlayer(playerUuid), TIMEOUT);
            if (online != null && online.isOnline()) {
                return eco.scheduler().callEntity(online, () -> operation.apply(online), TIMEOUT);
            }
            return eco.scheduler().callGlobal(
                    () -> operation.apply(Bukkit.getOfflinePlayer(playerUuid)), TIMEOUT);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (ExecutionException | TimeoutException | RuntimeException failure) {
            eco.getLogger().warning("[Tax] Wallet dispatch failed: " + failure.getMessage());
            return fallback;
        }
    }

    private static String safeName(UUID playerUuid, String playerName) {
        return playerName == null || playerName.isBlank() ? playerUuid.toString() : playerName;
    }
}
