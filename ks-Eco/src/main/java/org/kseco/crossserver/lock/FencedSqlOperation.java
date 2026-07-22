package org.kseco.crossserver.lock;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * A short SQL-only operation executed while the lease row is write-locked.
 *
 * <p>Persist the supplied fencing token with the operation's idempotency key.
 * Do not invoke Bukkit, Vault, futures, or a server-thread callback here.</p>
 */
@FunctionalInterface
public interface FencedSqlOperation<T> {
    T execute(Connection connection, long fencingToken) throws SQLException;
}
