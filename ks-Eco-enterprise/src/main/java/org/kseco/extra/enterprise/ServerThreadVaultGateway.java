package org.kseco.extra.enterprise;

import org.bukkit.Bukkit;
import org.kseco.KsEco;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Keeps every Bukkit player lookup and Vault provider call on the server thread. */
final class ServerThreadVaultGateway {

    private static final long SERVER_THREAD_TIMEOUT_SECONDS = 10;

    private final Dispatcher dispatcher;
    private final VaultAccess access;
    private final Consumer<String> warningSink;

    ServerThreadVaultGateway(KsEco eco) {
        this(
                new Dispatcher() {
                    @Override
                    public <T> T call(Callable<T> action) throws Exception {
                        if (Bukkit.isPrimaryThread()) return action.call();
                        Future<T> future = Bukkit.getScheduler().callSyncMethod(eco, action);
                        try {
                            return future.get(SERVER_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        } catch (Exception failure) {
                            future.cancel(true);
                            throw failure;
                        }
                    }
                },
                new VaultAccess() {
                    @Override
                    public boolean isAvailable() {
                        return eco.vaultHook().isAvailable();
                    }

                    @Override
                    public boolean has(UUID playerUuid, double amount) {
                        return eco.vaultHook().has(Bukkit.getOfflinePlayer(playerUuid), amount);
                    }

                    @Override
                    public boolean withdraw(UUID playerUuid, double amount) {
                        return eco.vaultHook().withdraw(Bukkit.getOfflinePlayer(playerUuid), amount);
                    }

                    @Override
                    public boolean deposit(UUID playerUuid, double amount) {
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
        return invoke("availability check", access::isAvailable, false);
    }

    boolean has(UUID playerUuid, double amount) {
        return invoke("balance check for " + playerUuid, () -> access.has(playerUuid, amount), false);
    }

    boolean withdraw(UUID playerUuid, double amount) {
        return invoke("withdrawal for " + playerUuid, () -> access.withdraw(playerUuid, amount), false);
    }

    boolean deposit(UUID playerUuid, double amount) {
        return invoke("deposit for " + playerUuid, () -> access.deposit(playerUuid, amount), false);
    }

    BatchDepositResult depositAll(Map<UUID, Double> payouts) {
        Map<UUID, Double> snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(payouts));
        try {
            Map<UUID, DepositStatus> outcomes = dispatcher.call(() -> {
                Map<UUID, DepositStatus> result = new LinkedHashMap<>();
                for (Map.Entry<UUID, Double> entry : snapshot.entrySet()) {
                    try {
                        result.put(entry.getKey(), access.deposit(entry.getKey(), entry.getValue())
                                ? DepositStatus.PAID : DepositStatus.REJECTED);
                    } catch (Exception e) {
                        result.put(entry.getKey(), DepositStatus.UNKNOWN);
                    }
                }
                return result;
            });
            return new BatchDepositResult(Map.copyOf(outcomes), true, null);
        } catch (Exception e) {
            warningSink.accept("Server-thread deposit dispatch failed: " + describe(e));
            Map<UUID, DepositStatus> outcomes = new LinkedHashMap<>();
            snapshot.keySet().forEach(uuid -> outcomes.put(uuid, DepositStatus.UNKNOWN));
            return new BatchDepositResult(Map.copyOf(outcomes), false, describe(e));
        }
    }

    private <T> T invoke(String operation, Callable<T> action, T fallback) {
        try {
            return dispatcher.call(action);
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
        <T> T call(Callable<T> action) throws Exception;
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
