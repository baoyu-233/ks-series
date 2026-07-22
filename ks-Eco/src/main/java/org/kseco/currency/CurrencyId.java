package org.kseco.currency;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Canonical, database-safe currency identifier. */
public record CurrencyId(String value) implements Comparable<CurrencyId> {
    private static final Pattern VALID = Pattern.compile("[A-Z][A-Z0-9_.-]{0,31}");

    public CurrencyId {
        Objects.requireNonNull(value, "value");
        value = value.trim().toUpperCase(Locale.ROOT);
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Currency id must match [A-Z][A-Z0-9_.-]{0,31}: " + value);
        }
    }

    public static CurrencyId of(String value) {
        return new CurrencyId(value);
    }

    @Override
    public int compareTo(CurrencyId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
