package org.kseco.crossserver.lock;

import java.sql.SQLException;

/**
 * Injectable short-transaction boundary used by the lease implementation.
 *
 * <p>The caller chooses the worker lane. Implementations must commit on normal
 * return, roll back on failure, and must never wait for a Bukkit/main-thread
 * callback while the transaction is open.</p>
 */
public interface LeaseSqlExecutor {
    <T> T inTransaction(LeaseSqlWork<T> work) throws SQLException;
}
