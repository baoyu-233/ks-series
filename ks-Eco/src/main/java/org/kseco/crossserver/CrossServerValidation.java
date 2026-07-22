package org.kseco.crossserver;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

final class CrossServerValidation {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]*");

    private CrossServerValidation() {
    }

    static String identifier(String value, String name, int maxLength) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty() || normalized.length() > maxLength || !IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be an ASCII identifier of at most " + maxLength + " characters");
        }
        return normalized;
    }

    static String text(String value, String name, int maxLength) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank() || checked.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be non-blank and at most " + maxLength + " characters");
        }
        return checked;
    }

    static Instant instant(Instant value, String name) {
        return Objects.requireNonNull(value, name);
    }

    static Duration positiveDuration(Duration value, String name) {
        Duration checked = Objects.requireNonNull(value, name);
        if (checked.isZero() || checked.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return checked;
    }

    static void notBefore(Instant earlier, Instant later, String laterName) {
        if (later.isBefore(earlier)) {
            throw new IllegalArgumentException(laterName + " must not be before " + earlier);
        }
    }
}
