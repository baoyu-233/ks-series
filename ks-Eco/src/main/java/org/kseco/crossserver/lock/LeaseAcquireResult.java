package org.kseco.crossserver.lock;

import java.time.Instant;
import java.util.Objects;

/** Result of a non-blocking lease acquisition attempt. */
public sealed interface LeaseAcquireResult
        permits LeaseAcquireResult.Acquired, LeaseAcquireResult.Busy {

    record Acquired(LeaseToken token) implements LeaseAcquireResult {
        public Acquired {
            Objects.requireNonNull(token, "token");
        }
    }

    record Busy(String resourceKey, Instant retryAt) implements LeaseAcquireResult {
        public Busy {
            resourceKey = LeaseToken.requireIdentifier(resourceKey, "resourceKey");
            Objects.requireNonNull(retryAt, "retryAt");
        }
    }
}
