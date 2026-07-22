package org.kseco.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;

/** Transactional singleton bootstrap for the system central-bank row. */
public final class CentralBankBootstrap {
    private static final String SINGLETON_KEY = "CENTRAL_BANK";

    private CentralBankBootstrap() {
    }

    public record Result(String bankId, boolean created) {
    }

    public static Result ensure(Connection connection, Supplier<String> idSupplier, long now) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(idSupplier, "idSupplier");
        if (!connection.getAutoCommit()) {
            throw new SQLException("Central-bank bootstrap requires an owned auto-commit connection");
        }

        connection.setAutoCommit(false);
        try {
            String claimedId = singletonId(connection);
            if (claimedId != null && centralBankExists(connection, claimedId)) {
                connection.commit();
                return new Result(claimedId, false);
            }
            if (claimedId != null) {
                try (var delete = connection.prepareStatement(
                        "DELETE FROM ks_bank_system_singletons WHERE singleton_key=? AND resource_id=?")) {
                    delete.setString(1, SINGLETON_KEY);
                    delete.setString(2, claimedId);
                    if (delete.executeUpdate() != 1) throw new SQLException("Central-bank singleton changed during repair");
                }
            }

            String existingId = firstCentralBankId(connection);
            if (existingId != null) {
                claimSingleton(connection, existingId, now);
                String winner = singletonId(connection);
                connection.commit();
                return new Result(winner == null ? existingId : winner, false);
            }

            String newId = Objects.requireNonNull(idSupplier.get(), "central bank id");
            if (newId.isBlank() || newId.length() > 64) throw new SQLException("Invalid central-bank id");
            if (!claimSingleton(connection, newId, now)) {
                String winner = singletonId(connection);
                if (winner == null) throw new SQLException("Central-bank singleton race did not expose a winner");
                connection.commit();
                return new Result(winner, false);
            }

            try (var insert = connection.prepareStatement(
                    "INSERT INTO ks_bank_banks "
                            + "(id,name,type,owner_uuids,total_assets,created_at) VALUES (?,?,?,?,?,?)")) {
                insert.setString(1, newId);
                insert.setString(2, "中央银行");
                insert.setString(3, "CENTRAL");
                insert.setString(4, "SYSTEM");
                insert.setDouble(5, 100_000_000D);
                insert.setLong(6, now);
                if (insert.executeUpdate() != 1) throw new SQLException("Central-bank insert affected an unexpected row count");
            }
            try (var rate = connection.prepareStatement(
                    "INSERT INTO ks_bank_cb_rates (base_rate,reserve_requirement,set_at) VALUES (?,?,?)")) {
                rate.setDouble(1, 0.035D);
                rate.setDouble(2, 0.10D);
                rate.setLong(3, now);
                if (rate.executeUpdate() != 1) throw new SQLException("Central-bank rate insert failed");
            }
            insertConfigIfAbsent(connection, "rate_adjust_limit", "0.02");
            connection.commit();
            return new Result(newId, true);
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static boolean claimSingleton(Connection connection, String bankId, long now) throws SQLException {
        return PortableSqlMutation.insertIfAbsent(connection,
                "SELECT 1 FROM ks_bank_system_singletons WHERE singleton_key=?",
                statement -> statement.setString(1, SINGLETON_KEY),
                "INSERT INTO ks_bank_system_singletons (singleton_key,resource_id,updated_at) VALUES (?,?,?)",
                statement -> {
                    statement.setString(1, SINGLETON_KEY);
                    statement.setString(2, bankId);
                    statement.setLong(3, now);
                });
    }

    private static void insertConfigIfAbsent(Connection connection, String key, String value) throws SQLException {
        PortableSqlMutation.insertIfAbsent(connection,
                "SELECT 1 FROM ks_bank_cb_config WHERE config_key=?",
                statement -> statement.setString(1, key),
                "INSERT INTO ks_bank_cb_config (config_key,config_value) VALUES (?,?)",
                statement -> {
                    statement.setString(1, key);
                    statement.setString(2, value);
                });
    }

    private static String singletonId(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT resource_id FROM ks_bank_system_singletons WHERE singleton_key=?")) {
            query.setString(1, SINGLETON_KEY);
            try (var rows = query.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static boolean centralBankExists(Connection connection, String bankId) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT 1 FROM ks_bank_banks WHERE id=? AND type='CENTRAL'")) {
            query.setString(1, bankId);
            try (var rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static String firstCentralBankId(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT id FROM ks_bank_banks WHERE type='CENTRAL' ORDER BY created_at,id")) {
            try (var rows = query.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }
}
