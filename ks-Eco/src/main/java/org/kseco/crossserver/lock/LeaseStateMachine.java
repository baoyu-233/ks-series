package org.kseco.crossserver.lock;

import java.util.Objects;

/** Pure Java state transition core, intentionally package-private for fixtures. */
final class LeaseStateMachine {
    private LeaseToken token;
    private LeaseState state = LeaseState.ACTIVE;

    LeaseStateMachine(LeaseToken token) {
        this.token = Objects.requireNonNull(token, "token");
    }

    LeaseState state() {
        return state;
    }

    LeaseToken token() {
        return token;
    }

    boolean isActive() {
        return state == LeaseState.ACTIVE;
    }

    void renewed(LeaseToken renewedToken) {
        requireActive();
        Objects.requireNonNull(renewedToken, "renewedToken");
        if (!token.sameLease(renewedToken)) {
            throw new IllegalArgumentException("Renewal must preserve lease identity and fencing token");
        }
        if (renewedToken.leaseUntil().isBefore(token.leaseUntil())) {
            throw new IllegalArgumentException("Renewal must not shorten a lease");
        }
        token = renewedToken;
    }

    void lost() {
        if (state == LeaseState.ACTIVE) {
            state = LeaseState.LOST;
        }
    }

    LeaseReleaseResult released() {
        if (state == LeaseState.RELEASED) {
            return LeaseReleaseResult.ALREADY_RELEASED;
        }
        if (state == LeaseState.LOST) {
            return LeaseReleaseResult.LOST;
        }
        state = LeaseState.RELEASED;
        return LeaseReleaseResult.RELEASED;
    }

    private void requireActive() {
        if (state != LeaseState.ACTIVE) {
            throw new IllegalStateException("Lease is " + state);
        }
    }
}
