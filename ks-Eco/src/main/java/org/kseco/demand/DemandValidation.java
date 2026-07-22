package org.kseco.demand;

import java.util.Objects;
import java.util.regex.Pattern;

final class DemandValidation {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@/-]*");

    private DemandValidation() {}

    static String requireIdentifier(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.length() > maximumLength || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is not a valid identifier");
        }
        return value;
    }
}
