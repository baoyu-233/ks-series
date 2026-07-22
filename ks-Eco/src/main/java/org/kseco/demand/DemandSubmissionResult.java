package org.kseco.demand;

import java.util.Objects;

public record DemandSubmissionResult(
        Outcome outcome,
        Code code,
        boolean replayed,
        long acceptedQuantity,
        long payoutMinor
) {
    public enum Outcome {
        ACCEPTED,
        REJECTED,
        IDEMPOTENCY_CONFLICT
    }

    public enum Code {
        ACCEPTED,
        PARTIALLY_ACCEPTED,
        UNSUPPORTED_CURRENCY,
        CAMPAIGN_NOT_FOUND,
        CAMPAIGN_NOT_ACTIVE,
        CAMPAIGN_NOT_STARTED,
        CAMPAIGN_EXPIRED,
        CAMPAIGN_EXHAUSTED,
        ITEM_MISMATCH,
        PRICE_MISMATCH,
        PLAYER_LIMIT_REACHED,
        IDEMPOTENCY_CONFLICT
    }

    public DemandSubmissionResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(code, "code");
        if (acceptedQuantity < 0L || payoutMinor < 0L) {
            throw new IllegalArgumentException("accepted quantity and payout cannot be negative");
        }
        if (outcome != Outcome.ACCEPTED && (acceptedQuantity != 0L || payoutMinor != 0L)) {
            throw new IllegalArgumentException("rejected operations cannot reserve quantity or budget");
        }
    }

    static DemandSubmissionResult accepted(Code code, boolean replayed, long quantity, long payoutMinor) {
        return new DemandSubmissionResult(Outcome.ACCEPTED, code, replayed, quantity, payoutMinor);
    }

    static DemandSubmissionResult rejected(Code code, boolean replayed) {
        return new DemandSubmissionResult(Outcome.REJECTED, code, replayed, 0L, 0L);
    }

    static DemandSubmissionResult conflict() {
        return new DemandSubmissionResult(Outcome.IDEMPOTENCY_CONFLICT, Code.IDEMPOTENCY_CONFLICT,
                true, 0L, 0L);
    }
}
