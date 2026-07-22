package org.kseco.extra.enterprise;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.kseco.KsEco;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Keeps Bukkit/Vault work on the online player's entity owner or the offline global owner. */
final class ServerThreadVaultGateway {

    private static final Duration SERVER_THREAD_TIMEOUT = Duration.ofSeconds(10);

    private final Dispatcher dispatcher;
    private final VaultAccess access;
    private final Consumer<String> warningSink;

    ServerThreadVaultGateway(KsEco eco) {
        this(
                new Dispatcher() {
                    @Override
                    public <T> T call(UUID playerUuid, Callable<T> action) throws Exception {
                        if (eco.vaultHook().directBuiltinActive()) return action.call();
                        if (playerUuid == null) {
                            return eco.scheduler().callGlobal(action, SERVER_THREAD_TIMEOUT);
                        }
                        Player online = eco.scheduler().callGlobal(
                                () -> Bukkit.getPlayer(playerUuid), SERVER_THREAD_TIMEOUT);
                        return online != null && online.isOnline()
                                ? eco.scheduler().callEntity(online, action, SERVER_THREAD_TIMEOUT)
                                : eco.scheduler().callGlobal(action, SERVER_THREAD_TIMEOUT);
                    }
                },
                new VaultAccess() {
                    @Override
                    public boolean isAvailable() {
                        return eco.vaultHook().isAvailable();
                    }

                    @Override
                    public boolean has(UUID playerUuid, double amount) {
                        if (eco.vaultHook().directBuiltinActive()) {
                            return eco.vaultHook().hasDirect(playerUuid, amount);
                        }
                        return eco.vaultHook().has(Bukkit.getOfflinePlayer(playerUuid), amount);
                    }

                    @Override
                    public boolean withdraw(UUID playerUuid, double amount) {
                        if (eco.vaultHook().directBuiltinActive()) {
                            return eco.vaultHook().withdrawDirect(playerUuid, playerUuid.toString(), amount);
                        }
                        return eco.vaultHook().withdraw(Bukkit.getOfflinePlayer(playerUuid), amount);
                    }

                    @Override
                    public boolean deposit(UUID playerUuid, double amount) {
                        if (eco.vaultHook().directBuiltinActive()) {
                            return eco.vaultHook().depositDirect(playerUuid, playerUuid.toString(), amount);
                        }
                        return eco.vaultHook().deposit(Bukkit.getOfflinePlayer(playerUuid), amount);
                    }
                },
                message -> eco.getLogger().warning("[Enterprise Vault] " + message)
        );
    }

    ServerThreadVaultGateway(Dispatcher dispatcher, VaultAccess access, Consumer<String> warningSink) {
        this.dispatcher = dispatcher;
        this.access = access;
        this.warningSink = warningSink;
    }

    boolean isAvailable() {
        return invoke(null, "availability check", access::isAvailable, false);
    }

    boolean has(UUID playerUuid, double amount) {
        return invoke(playerUuid, "balance check for " + playerUuid, () -> access.has(playerUuid, amount), false);
    }

    boolean withdraw(UUID playerUuid, double amount) {
        return invoke(playerUuid, "withdrawal for " + playerUuid, () -> access.withdraw(playerUuid, amount), false);
    }

    boolean deposit(UUID playerUuid, double amount) {
        return invoke(playerUuid, "deposit for " + playerUuid, () -> access.deposit(playerUuid, amount), false);
    }

    BatchDepositResult depositAll(Map<UUID, Double> payouts) {
        Map<UUID, Double> snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(payouts));
        Map<UUID, DepositStatus> outcomes = new LinkedHashMap<>();
        boolean dispatchCompleted = true;
        String error = null;
        for (Map.Entry<UUID, Double> entry : snapshot.entrySet()) {
            try {
                boolean paid = dispatcher.call(entry.getKey(),
                        () -> access.deposit(entry.getKey(), entry.getValue()));
                outcomes.put(entry.getKey(), paid ? DepositStatus.PAID : DepositStatus.REJECTED);
            } catch (Exception failure) {
                dispatchCompleted = false;
                error = describe(failure);
                outcomes.put(entry.getKey(), DepositStatus.UNKNOWN);
                warningSink.accept("Server-thread deposit dispatch failed: " + error);
            }
        }
        return new BatchDepositResult(Map.copyOf(outcomes), dispatchCompleted, error);
    }

    private <T> T invoke(UUID playerUuid, String operation, Callable<T> action, T fallback) {
        try {
            return dispatcher.call(playerUuid, action);
        } catch (Exception e) {
            warningSink.accept("Server-thread " + operation + " failed: " + describe(e));
            return fallback;
        }
    }

    private static String describe(Exception exception) {
        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    @FunctionalInterface
    interface Dispatcher {
        <T> T call(UUID playerUuid, Callable<T> action) throws Exception;
    }

    interface VaultAccess {
        boolean isAvailable();

        boolean has(UUID playerUuid, double amount);

        boolean withdraw(UUID playerUuid, double amount);

        boolean deposit(UUID playerUuid, double amount);
    }

    enum DepositStatus {
        PAID,
        REJECTED,
        UNKNOWN
    }

    record BatchDepositResult(Map<UUID, DepositStatus> outcomes, boolean dispatchCompleted, String error) {
        boolean allPaid() {
            return outcomes.values().stream().allMatch(status -> status == DepositStatus.PAID);
        }
    }
}
