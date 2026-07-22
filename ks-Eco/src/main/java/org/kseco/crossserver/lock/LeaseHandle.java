package org.kseco.crossserver.lock;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;

/**
 * Fail-closed local state machine for one acquired lease.
 *
 * <p>Any database error during mutation marks the handle LOST because the
 * remote outcome can be uncertain. Terminal states never become ACTIVE again.</p>
 */
public final class LeaseHandle {
    private final DistributedLeaseLock lock;
    private final LeaseStateMachine machine;

    public LeaseHandle(DistributedLeaseLock lock, LeaseToken token) {
        this.lock = Objects.requireNonNull(lock, "lock");
        this.machine = new LeaseStateMachine(token);
    }

    public synchronized LeaseState state() {
        return machine.state();
    }

    public synchronized LeaseToken token() {
        return machine.token();
    }

    public synchronized LeaseRenewResult renew(Duration leaseDuration) throws SQLException {
        if (!machine.isActive()) {
            return new LeaseRenewResult.Lost();
        }
        try {
            LeaseRenewResult result = lock.renew(machine.token(), leaseDuration);
            if (result instanceof LeaseRenewResult.Renewed renewed) {
                machine.renewed(renewed.token());
            } else {
                machine.lost();
            }
            return result;
        } catch (SQLException | RuntimeException failure) {
            machine.lost();
            throw failure;
        }
    }

    public synchronized LeaseCheckResult check() throws SQLException {
        if (!machine.isActive()) {
            return LeaseCheckResult.LOST;
        }
        try {
            LeaseCheckResult result = lock.check(machine.token());
            if (result == LeaseCheckResult.LOST) {
                machine.lost();
            }
            return result;
        } catch (SQLException | RuntimeException failure) {
            machine.lost();
            throw failure;
        }
    }

    public synchronized <T> FencedExecutionResult<T> executeFenced(FencedSqlOperation<T> operation)
            throws SQLException {
        Objects.requireNonNull(operation, "operation");
        if (!machine.isActive()) {
            return new FencedExecutionResult.LeaseLost<>();
        }
        try {
            FencedExecutionResult<T> result = lock.executeFenced(machine.token(), operation);
            if (result instanceof FencedExecutionResult.LeaseLost<?>) {
                machine.lost();
            }
            return result;
        } catch (SQLException | RuntimeException failure) {
            machine.lost();
            throw failure;
        }
    }

    public synchronized LeaseReleaseResult release() throws SQLException {
        if (machine.state() == LeaseState.RELEASED) {
            return LeaseReleaseResult.ALREADY_RELEASED;
        }
        if (!machine.isActive()) {
            return LeaseReleaseResult.LOST;
        }
        try {
            LeaseReleaseResult result = lock.release(machine.token());
            if (result == LeaseReleaseResult.RELEASED) {
                return machine.released();
            }
            machine.lost();
            return LeaseReleaseResult.LOST;
        } catch (SQLException | RuntimeException failure) {
            machine.lost();
            throw failure;
        }
    }
}
