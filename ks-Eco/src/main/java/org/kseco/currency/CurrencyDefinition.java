package org.kseco.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable definition used to convert display units to exact minor units. */
public record CurrencyDefinition(
        CurrencyId id,
        String displayName,
        String symbol,
        int scale,
        RoundingMode roundingMode,
        Set<CurrencyFeature> features
) {
    public static final int MAX_SCALE = 8;

    public CurrencyDefinition {
        Objects.requireNonNull(id, "id");
        displayName = requireText(displayName, "displayName", 64);
        symbol = requireText(symbol, "symbol", 16);
        if (scale < 0 || scale > MAX_SCALE) {
            throw new IllegalArgumentException("Currency scale must be between 0 and " + MAX_SCALE);
        }
        Objects.requireNonNull(roundingMode, "roundingMode");
        if (features == null || features.isEmpty()) {
            features = Set.of();
        } else {
            features = Collections.unmodifiableSet(EnumSet.copyOf(features));
        }
    }

    public long toMinorUnits(BigDecimal majorUnits) {
        Objects.requireNonNull(majorUnits, "majorUnits");
        return majorUnits.movePointRight(scale)
                .setScale(0, roundingMode)
                .longValueExact();
    }

    public BigDecimal toMajorUnits(long minorUnits) {
        return BigDecimal.valueOf(minorUnits, scale);
    }

    public Money money(BigDecimal majorUnits) {
        return new Money(id, toMinorUnits(majorUnits));
    }

    public boolean supports(CurrencyFeature feature) {
        return features.contains(Objects.requireNonNull(feature, "feature"));
    }

    private static String requireText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain 1-" + maxLength + " characters");
        }
        return trimmed;
    }
}
