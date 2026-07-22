package org.kseco.crossserver;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Blocking JDBC contract for shared-database cross-server coordination.
 *
 * <p>Every method must be invoked from the database worker lane. Implementations
 * must not schedule callbacks, touch Bukkit/Vault objects, or retain the supplied
 * connection after returning. Passing a connection allows a producer to enqueue
 * its event in the same transaction as the business change.</p>
 */
public interface CrossServerRepository {
    /** Creates or migrates only the cross-server coordination tables. */
    void initialize(Connection connection) throws SQLException;

    /**
     * Returns database-authoritative time. Lease, heartbeat, and retry decisions
     * must use this value rather than a game server's potentially skewed clock.
     */
    Instant currentDatabaseTime(Connection connection) throws SQLException;

    /** Returns false when the event ID already exists, making producer retries idempotent. */
    boolean enqueueOutbox(
            Connection connection,
            CrossServerEventEnvelope event,
            Instant availableAt
    ) throws SQLException;

    /** Atomically leases at most {@code limit} currently available outbox rows. */
    List<OutboxClaim> claimOutbox(
            Connection connection,
            String leaseOwnerId,
            Instant now,
            Duration leaseDuration,
            int limit
    ) throws SQLException;

    /** Completes a row only when it is still owned by the supplied lease owner. */
    boolean markOutboxPublished(
            Connection connection,
            UUID eventId,
            String leaseOwnerId,
            int expectedAttemptCount,
            Instant publishedAt
    ) throws SQLException;

    /** Releases a failed claim for retry at or after {@code availableAt}. */
    boolean retryOutbox(
            Connection connection,
            UUID eventId,
            String leaseOwnerId,
            int expectedAttemptCount,
            Instant availableAt,
            String lastError
    ) throws SQLException;

    int purgePublishedOutbox(Connection connection, Instant publishedBefore, int limit) throws SQLException;

    /**
     * Claims the unique (consumerId, eventId) inbox key. A processed result must
     * never execute its side effect again; a busy result is retried after its lease.
     */
    InboxClaim claimInbox(
            Connection connection,
            CrossServerEventEnvelope event,
            String consumerId,
            String leaseOwnerId,
            Instant now,
            Duration leaseDuration
    ) throws SQLException;

    boolean markInboxProcessed(
            Connection connection,
            UUID eventId,
            String consumerId,
            String leaseOwnerId,
            int expectedAttemptCount,
            Instant processedAt
    ) throws SQLException;

    boolean markInboxFailed(
            Connection connection,
            UUID eventId,
            String consumerId,
            String leaseOwnerId,
            int expectedAttemptCount,
            Instant retryAt,
            String lastError
    ) throws SQLException;

    int purgeProcessedInbox(Connection connection, Instant processedBefore, int limit) throws SQLException;

    /** Upserts the logical server row, replacing an older process instance atomically. */
    void heartbeat(Connection connection, ServerHeartbeat heartbeat) throws SQLException;

    List<ServerHeartbeat> findAliveServers(Connection connection, Instant now) throws SQLException;

    int purgeExpiredHeartbeats(Connection connection, Instant expiredBefore, int limit) throws SQLException;

    /**
     * Acquires or renews a lease. A takeover must increment the fencing token;
     * an empty result means another live owner currently holds it.
     */
    Optional<LeaseRecord> tryAcquireLease(
            Connection connection,
            String leaseName,
            String ownerId,
            Instant now,
            Duration leaseDuration
    ) throws SQLException;

    boolean renewLease(
            Connection connection,
            String leaseName,
            String ownerId,
            long fencingToken,
            Instant now,
            Duration leaseDuration
    ) throws SQLException;

    boolean releaseLease(
            Connection connection,
            String leaseName,
            String ownerId,
            long fencingToken,
            Instant releasedAt
    ) throws SQLException;

    Optional<LeaseRecord> findLease(Connection connection, String leaseName) throws SQLException;
}
