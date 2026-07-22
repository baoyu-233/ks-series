package org.kseco.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Savepoint;

/**
 * Narrow update-then-insert helpers for business tables whose natural key is
 * already protected by a primary or unique constraint.
 */
public final class PortableSqlMutation {
    private PortableSqlMutation() {
    }

    @FunctionalInterface
    public interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    public static void upsert(
            Connection connection,
            String updateSql,
            Binder updateBinder,
            String insertSql,
            Binder insertBinder) throws SQLException {
        int updated = execute(connection, updateSql, updateBinder);
        requireAtMostOne(updated, "update");
        if (updated == 1) {
            return;
        }

        Savepoint savepoint = insertSavepoint(connection);
        try {
            requireExactlyOne(execute(connection, insertSql, insertBinder), "insert");
            releaseSavepoint(connection, savepoint);
        } catch (SQLException insertFailure) {
            rollbackInsert(connection, savepoint, insertFailure);
            if (!isConstraintViolation(insertFailure)) {
                throw insertFailure;
            }
            // A concurrent writer may have inserted the natural key after our
            // first UPDATE. Retrying UPDATE distinguishes that race from a real
            // integrity failure after restoring transactional usability.
            int retried = execute(connection, updateSql, updateBinder);
            requireAtMostOne(retried, "retry update");
            if (retried == 1) {
                return;
            }
            throw insertFailure;
        }
    }

    public static boolean insertIfAbsent(
            Connection connection,
            String existsSql,
            Binder existsBinder,
            String insertSql,
            Binder insertBinder) throws SQLException {
        if (exists(connection, existsSql, existsBinder)) {
            return false;
        }
        Savepoint savepoint = insertSavepoint(connection);
        try {
            requireExactlyOne(execute(connection, insertSql, insertBinder), "insert-if-absent");
            releaseSavepoint(connection, savepoint);
            return true;
        } catch (SQLException insertFailure) {
            rollbackInsert(connection, savepoint, insertFailure);
            if (!isConstraintViolation(insertFailure)) {
                throw insertFailure;
            }
            if (exists(connection, existsSql, existsBinder)) {
                return false;
            }
            throw insertFailure;
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
            try (var result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static Savepoint insertSavepoint(Connection connection) throws SQLException {
        return connection.getAutoCommit() ? null : connection.setSavepoint();
    }

    private static void releaseSavepoint(Connection connection, Savepoint savepoint) throws SQLException {
        if (savepoint != null) {
            connection.releaseSavepoint(savepoint);
        }
    }

    private static void rollbackInsert(Connection connection, Savepoint savepoint, SQLException insertFailure)
            throws SQLException {
        if (savepoint == null) {
            return;
        }
        try {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        } catch (SQLException rollbackFailure) {
            insertFailure.addSuppressed(rollbackFailure);
            throw insertFailure;
        }
    }

    private static boolean isConstraintViolation(SQLException failure) {
        for (SQLException current = failure; current != null; current = current.getNextException()) {
            if (current instanceof SQLIntegrityConstraintViolationException
                    || (current.getSQLState() != null && current.getSQLState().startsWith("23"))
                    || current.getErrorCode() == 19
                    || current.getErrorCode() == 1062) {
                return true;
            }
        }
        return false;
    }

    private static void requireExactlyOne(int affected, String operation) throws SQLException {
        if (affected != 1) {
            throw new SQLException("Portable SQL " + operation + " affected " + affected + " rows");
        }
    }

    private static void requireAtMostOne(int affected, String operation) throws SQLException {
        if (affected < 0 || affected > 1) {
            throw new SQLException("Portable SQL " + operation + " affected " + affected + " rows");
        }
    }
}
