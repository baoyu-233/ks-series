package org.kseco.currency;

import java.util.Map;
import java.util.Objects;

public record LedgerResult(
        Outcome outcome,
        boolean replayed,
        String code,
        Map<BalanceKey, Money> balancesAfter
) {
    public enum Outcome {
        APPLIED,
        REJECTED,
        IDEMPOTENCY_CONFLICT
    }

    public static final String CODE_APPLIED = "APPLIED";
    public static final String CODE_INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    public static final String CODE_IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";

    public LedgerResult {
        Objects.requireNonNull(outcome, "outcome");
        code = Objects.requireNonNull(code, "code").trim();
        if (code.isEmpty() || code.length() > 64) {
            throw new IllegalArgumentException("Ledger result code must contain 1-64 characters");
        }
        balancesAfter = Map.copyOf(Objects.requireNonNull(balancesAfter, "balancesAfter"));
    }

    public static LedgerResult applied(boolean replayed, Map<BalanceKey, Money> balancesAfter) {
        return new LedgerResult(Outcome.APPLIED, replayed, CODE_APPLIED, balancesAfter);
    }

    public static LedgerResult rejected(String code, boolean replayed) {
        return new LedgerResult(Outcome.REJECTED, replayed, code, Map.of());
    }

    public static LedgerResult idempotencyConflict() {
        return new LedgerResult(
                Outcome.IDEMPOTENCY_CONFLICT,
                false,
                CODE_IDEMPOTENCY_CONFLICT,
                Map.of());
    }

    public boolean success() {
        return outcome == Outcome.APPLIED;
    }
}
