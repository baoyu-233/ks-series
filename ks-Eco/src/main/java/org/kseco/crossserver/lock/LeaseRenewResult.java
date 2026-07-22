package org.kseco.crossserver.lock;

import java.util.Objects;

/** Result of renewing an existing lease generation. */
public sealed interface LeaseRenewResult
        permits LeaseRenewResult.Renewed, LeaseRenewResult.Lost {

    record Renewed(LeaseToken token) implements LeaseRenewResult {
        public Renewed {
            Objects.requireNonNull(token, "token");
        }
    }

    record Lost() implements LeaseRenewResult {}
}
