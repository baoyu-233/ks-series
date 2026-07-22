package org.kseco.currency;

import java.util.Objects;

/** One account balance delta within an atomic ledger mutation. */
public record LedgerPosting(CurrencyAccount account, Money delta, String memo) {
    public LedgerPosting {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(delta, "delta");
        if (delta.minorUnits() == 0L) {
            throw new IllegalArgumentException("Ledger posting delta must not be zero");
        }
        memo = memo == null ? "" : memo.trim();
        if (memo.length() > 255) {
            throw new IllegalArgumentException("Ledger posting memo is longer than 255 characters");
        }
    }

    public BalanceKey balanceKey() {
        return new BalanceKey(account, delta.currency());
    }
}
