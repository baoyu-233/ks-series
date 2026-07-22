package org.kseco.currency;

import java.util.Objects;

public record BalanceKey(CurrencyAccount account, CurrencyId currency) {
    public BalanceKey {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(currency, "currency");
    }
}
