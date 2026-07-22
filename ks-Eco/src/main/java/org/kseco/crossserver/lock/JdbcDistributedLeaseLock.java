package org.kseco.crossserver.lock;

import org.kseco.crossserver.CrossServerRepository;
import org.kseco.crossserver.LeaseRecord;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * State-machine adapter over the shared cross-server JDBC repository.
 *
 * <p>The repository owns schema and dialect differences. Lease rows are retained
 * after release, so every takeover receives a strictly greater fencing token.</p>
 */
public final class JdbcDistributedLeaseLock implements DistributedLeaseLock {
    private final LeaseSqlExecutor executor;
    private final CrossServerRepository repository;

    public JdbcDistributedLeaseLock(
            LeaseSqlExecutor executor,
            CrossServerRepository repository
    ) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public void initializeSchema() throws SQLException {
        executor.inTransaction(connection -> {
            repository.initialize(connection);
            return null;
        });
    }

    @Override
    public LeaseAcquireResult tryAcquire(
            String resourceKey,
            String ownerId,
            Duration leaseDuration
    ) throws SQLException {
        String resource = LeaseToken.requireIdentifier(resourceKey, "resourceKey");
        String owner = LeaseToken.requireIdentifier(ownerId, "ownerId");
        Duration duration = requireDuration(leaseDuration);
        return executor.inTransaction(connection -> {
            Instant now = repository.currentDatabaseTime(connection);
            Optional<LeaseRecord> acquired = repository.tryAcquireLease(
                    connection,
                    resource,
                    owner,
                    now,
                    duration
            );
            if (acquired.isPresent()) {
                return new LeaseAcquireResult.Acquired(toToken(acquired.orElseThrow()));
            }
            LeaseRecord current = repository.findLease(connection, resource).orElse(null);
            Instant retryAt = current == null ? now.plusMillis(1L) : current.expiresAt();
            return new LeaseAcquireResult.Busy(resource, retryAt);
        });
    }

    @Override
    public LeaseRenewResult renew(LeaseToken token, Duration leaseDuration) throws SQLException {
        Objects.requireNonNull(token, "token");
        Duration duration = requireDuration(leaseDuration);
        return executor.inTransaction(connection -> {
            Instant now = repository.currentDatabaseTime(connection);
            boolean renewed = repository.renewLease(
                    connection,
                    token.resourceKey(),
                    token.ownerId(),
                    token.fencingToken(),
                    now,
                    duration
            );
            if (!renewed) {
                return new LeaseRenewResult.Lost();
            }
            LeaseRecord current = repository.findLease(connection, token.resourceKey()).orElse(null);
            if (!matches(current, token) || !current.expiresAt().isAfter(now)) {
                return new LeaseRenewResult.Lost();
            }
            return new LeaseRenewResult.Renewed(new LeaseToken(
                    token.resourceKey(),
                    token.ownerId(),
                    token.leaseId(),
                    token.fencingToken(),
                    token.acquiredAt(),
                    current.expiresAt()
            ));
        });
    }

    @Override
    public LeaseReleaseResult release(LeaseToken token) throws SQLException {
        Objects.requireNonNull(token, "token");
        return executor.inTransaction(connection -> {
            Instant now = repository.currentDatabaseTime(connection);
            LeaseRecord current = repository.findLease(connection, token.resourceKey()).orElse(null);
            if (!matches(current, token) || !current.expiresAt().isAfter(now)) {
                return LeaseReleaseResult.LOST;
            }
            return repository.releaseLease(
                    connection,
                    token.resourceKey(),
                    token.ownerId(),
                    token.fencingToken(),
                    now
            ) ? LeaseReleaseResult.RELEASED : LeaseReleaseResult.LOST;
        });
    }

    @Override
    public LeaseCheckResult check(LeaseToken token) throws SQLException {
        Objects.requireNonNull(token, "token");
        return executor.inTransaction(connection -> {
            Instant now = repository.currentDatabaseTime(connection);
            LeaseRecord current = repository.findLease(connection, token.resourceKey()).orElse(null);
            return matches(current, token) && current.expiresAt().isAfter(now)
                    ? LeaseCheckResult.HELD
                    : LeaseCheckResult.LOST;
        });
    }

    @Override
    public <T> FencedExecutionResult<T> executeFenced(
            LeaseToken token,
            FencedSqlOperation<T> operation
    ) throws SQLException {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(operation, "operation");
        return executor.inTransaction(connection -> {
            Instant now = repository.currentDatabaseTime(connection);
            LeaseRecord current = repository.findLease(connection, token.resourceKey()).orElse(null);
            if (!matches(current, token) || !current.expiresAt().isAfter(now)) {
                return new FencedExecutionResult.LeaseLost<>();
            }

            long remainingMillis = Math.max(1L, Duration.between(now, current.expiresAt()).toMillis());
            Duration rowLockDuration = Duration.ofMillis(Math.addExact(remainingMillis, 1L));
            boolean locked = repository.renewLease(
                    connection,
                    token.resourceKey(),
                    token.ownerId(),
                    token.fencingToken(),
                    now,
                    rowLockDuration
            );
            if (!locked) {
                return new FencedExecutionResult.LeaseLost<>();
            }

            T value = operation.execute(connection, token.fencingToken());
            return new FencedExecutionResult.Executed<>(value, token.fencingToken());
        });
    }

    private static boolean matches(LeaseRecord record, LeaseToken token) {
        return record != null
                && record.leaseName().equals(token.resourceKey())
                && record.ownerId().equals(token.ownerId())
                && record.fencingToken() == token.fencingToken();
    }

    private static LeaseToken toToken(LeaseRecord record) {
        return new LeaseToken(
                record.leaseName(),
                record.ownerId(),
                UUID.randomUUID(),
                record.fencingToken(),
                record.updatedAt(),
                record.expiresAt()
        );
    }

    private static Duration requireDuration(Duration duration) {
        Objects.requireNonNull(duration, "leaseDuration");
        final long millis;
        try {
            millis = duration.toMillis();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("leaseDuration is too large", failure);
        }
        if (millis <= 0L) {
            throw new IllegalArgumentException("leaseDuration must be at least one millisecond");
        }
        return Duration.ofMillis(millis);
    }
}
