package org.kseco.extra.bank;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Savepoint;

/** Update/insert helpers that preserve PostgreSQL transactions after duplicate-key races. */
final class BankSqlMutation {
    private BankSqlMutation() {
    }

    @FunctionalInterface
    interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    static void upsert(Connection connection, String updateSql, Binder updateBinder,
                       String insertSql, Binder insertBinder) throws SQLException {
        int updated = execute(connection, updateSql, updateBinder);
        requireAtMostOne(updated, "update");
        if (updated == 1) return;
        Savepoint savepoint = savepoint(connection);
        try {
            requireExactlyOne(execute(connection, insertSql, insertBinder), "insert");
            release(connection, savepoint);
        } catch (SQLException failure) {
            rollback(connection, savepoint, failure);
            if (!constraintViolation(failure)) throw failure;
            int retried = execute(connection, updateSql, updateBinder);
            requireAtMostOne(retried, "retry update");
            if (retried != 1) throw failure;
        }
    }

    static boolean insertIfAbsent(Connection connection, String existsSql, Binder existsBinder,
                                  String insertSql, Binder insertBinder) throws SQLException {
        if (exists(connection, existsSql, existsBinder)) return false;
        Savepoint savepoint = savepoint(connection);
        try {
            requireExactlyOne(execute(connection, insertSql, insertBinder), "insert-if-absent");
            release(connection, savepoint);
            return true;
        } catch (SQLException failure) {
            rollback(connection, savepoint, failure);
            if (!constraintViolation(failure)) throw failure;
            if (exists(connection, existsSql, existsBinder)) return false;
            throw failure;
        }
    }

    private static int execute(Connection connection, String sql, Binder binder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate();
        }
    }

    private static boolean exists(Connection connection, String sql, Binder binder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static Savepoint savepoint(Connection connection) throws SQLException {
        return connection.getAutoCommit() ? null : connection.setSavepoint();
    }

    private static void release(Connection connection, Savepoint savepoint) throws SQLException {
        if (savepoint != null) connection.releaseSavepoint(savepoint);
    }

    private static void rollback(Connection connection, Savepoint savepoint, SQLException failure)
            throws SQLException {
        if (savepoint == null) return;
        try {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            throw failure;
        }
    }

    private static boolean constraintViolation(SQLException failure) {
        for (SQLException current = failure; current != null; current = current.getNextException()) {
            if (current instanceof SQLIntegrityConstraintViolationException
                    || current.getSQLState() != null && current.getSQLState().startsWith("23")
                    || current.getErrorCode() == 19 || current.getErrorCode() == 1062) return true;
        }
        return false;
    }

    private static void requireExactlyOne(int affected, String operation) throws SQLException {
        if (affected != 1) throw new SQLException("Bank SQL " + operation + " affected " + affected + " rows");
    }

    private static void requireAtMostOne(int affected, String operation) throws SQLException {
        if (affected < 0 || affected > 1) {
            throw new SQLException("Bank SQL " + operation + " affected " + affected + " rows");
        }
    }
}
