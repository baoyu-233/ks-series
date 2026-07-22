package org.kseries.rpg.season;

import java.util.Locale;
import java.util.regex.Pattern;

final class SeasonIds {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_.:-]{0,127}");
    private static final Pattern SOURCE_KEY = Pattern.compile("[a-z0-9][a-z0-9_.:/-]{0,190}");

    private SeasonIds() { }

    static String require(String value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
        return normalized;
    }

    static String requireSourceKey(String value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!SOURCE_KEY.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
        return normalized;
    }
}
