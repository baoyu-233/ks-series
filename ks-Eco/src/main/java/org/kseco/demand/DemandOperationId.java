package org.kseco.demand;

import java.util.Objects;
import java.util.regex.Pattern;

/** Globally unique and case-sensitive demand operation identifier. */
public record DemandOperationId(String value) {
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,127}");

    public DemandOperationId {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid demand operation id: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
