package org.kseco.currency;

import java.math.BigDecimal;
import java.util.Objects;

/** Exact currency amount stored as signed minor units. */
public record Money(CurrencyId currency, long minorUnits) implements Comparable<Money> {
    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money zero(CurrencyId currency) {
        return new Money(currency, 0L);
    }

    public static Money ofMajor(CurrencyDefinition definition, BigDecimal majorUnits) {
        Objects.requireNonNull(definition, "definition");
        return definition.money(majorUnits);
    }

    public BigDecimal toMajor(CurrencyDefinition definition) {
        requireSameCurrency(definition.id());
        return definition.toMajorUnits(minorUnits);
    }

    public Money plus(Money other) {
        requireSameCurrency(other.currency);
        return new Money(currency, Math.addExact(minorUnits, other.minorUnits));
    }

    public Money minus(Money other) {
        requireSameCurrency(other.currency);
        return new Money(currency, Math.subtractExact(minorUnits, other.minorUnits));
    }

    public Money negate() {
        return new Money(currency, Math.negateExact(minorUnits));
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    public boolean isNegative() {
        return minorUnits < 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other.currency);
        return Long.compare(minorUnits, other.minorUnits);
    }

    private void requireSameCurrency(CurrencyId other) {
        if (!currency.equals(other)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " != " + other);
        }
    }
}
