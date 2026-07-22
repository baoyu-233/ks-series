package org.kseco.currency;

import java.util.Objects;
import java.util.regex.Pattern;

/** Globally unique operation key shared by every server instance. */
public record IdempotencyKey(String value) {
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,127}");

    public IdempotencyKey {
        Objects.requireNonNull(value, "value");
        value = value.trim();
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid idempotency key: " + value);
        }
    }

    public static IdempotencyKey of(String value) {
        return new IdempotencyKey(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
