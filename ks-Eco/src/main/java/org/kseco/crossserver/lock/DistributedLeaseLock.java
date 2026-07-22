package org.kseco.crossserver.lock;

import java.sql.SQLException;
import java.time.Duration;

/**
 * Shared-database lease API. All methods perform SQL and belong on a database
 * worker, never on the Paper server thread.
 */
public interface DistributedLeaseLock {
    LeaseAcquireResult tryAcquire(String resourceKey, String ownerId, Duration leaseDuration)
            throws SQLException;

    LeaseRenewResult renew(LeaseToken token, Duration leaseDuration) throws SQLException;

    LeaseReleaseResult release(LeaseToken token) throws SQLException;

    LeaseCheckResult check(LeaseToken token) throws SQLException;

    <T> FencedExecutionResult<T> executeFenced(
            LeaseToken token,
            FencedSqlOperation<T> operation
    ) throws SQLException;
}
