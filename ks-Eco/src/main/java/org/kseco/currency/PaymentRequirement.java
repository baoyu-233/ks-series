package org.kseco.currency;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** A product price with either one exclusive currency or explicit alternative prices. */
public final class PaymentRequirement {
    private final String reference;
    private final boolean exclusive;
    private final Map<CurrencyId, Money> prices;

    private PaymentRequirement(String reference, boolean exclusive, Collection<Money> prices) {
        this.reference = requireReference(reference);
        Objects.requireNonNull(prices, "prices");
        Map<CurrencyId, Money> indexed = new LinkedHashMap<>();
        for (Money price : prices) {
            Objects.requireNonNull(price, "price");
            if (!price.isPositive()) throw new IllegalArgumentException("Payment price must be positive");
            if (indexed.putIfAbsent(price.currency(), price) != null) {
                throw new IllegalArgumentException("Duplicate payment currency: " + price.currency());
            }
        }
        if (indexed.isEmpty()) {
            throw new IllegalArgumentException("Payment requirement must contain at least one price");
        }
        if (exclusive && indexed.size() != 1) {
            throw new IllegalArgumentException("Exclusive payment requires exactly one price");
        }
        if (!exclusive && indexed.size() < 2) {
            throw new IllegalArgumentException("Alternative payment requires at least two prices");
        }
        this.exclusive = exclusive;
        this.prices = Collections.unmodifiableMap(indexed);
    }

    public static PaymentRequirement exact(String reference, Money price) {
        return new PaymentRequirement(reference, true, List.of(price));
    }

    public static PaymentRequirement oneOf(String reference, Collection<Money> prices) {
        return new PaymentRequirement(reference, false, prices);
    }

    public String reference() {
        return reference;
    }

    public boolean exclusive() {
        return exclusive;
    }

    public List<Money> prices() {
        return List.copyOf(prices.values());
    }

    public Optional<Money> priceFor(CurrencyId currency) {
        return Optional.ofNullable(prices.get(Objects.requireNonNull(currency, "currency")));
    }

    public CurrencyId requiredCurrency() {
        if (!exclusive) throw new IllegalStateException("Payment requirement is not exclusive");
        return prices.keySet().iterator().next();
    }

    private static String requireReference(String value) {
        Objects.requireNonNull(value, "reference");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 255) {
            throw new IllegalArgumentException("Payment reference must contain 1-255 characters");
        }
        return trimmed;
    }
}
