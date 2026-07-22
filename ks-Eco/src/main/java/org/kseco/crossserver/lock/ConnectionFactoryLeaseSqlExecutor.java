package org.kseco.crossserver.lock;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Lease transaction executor for plugin-owned JDBC connection factories. */
public final class ConnectionFactoryLeaseSqlExecutor implements LeaseSqlExecutor {
    private final ConnectionFactory connectionFactory;

    public ConnectionFactoryLeaseSqlExecutor(ConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    @Override
    public <T> T inTransaction(LeaseSqlWork<T> work) throws SQLException {
        Objects.requireNonNull(work, "work");
        try (Connection connection = connectionFactory.open()) {
            if (connection == null) throw new SQLException("connection factory returned null");
            boolean originalAutoCommit = connection.getAutoCommit();
            if (originalAutoCommit) connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            } finally {
                if (!connection.isClosed() && connection.getAutoCommit() != originalAutoCommit) {
                    connection.setAutoCommit(originalAutoCommit);
                }
            }
        }
    }

    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open() throws SQLException;
    }
}
