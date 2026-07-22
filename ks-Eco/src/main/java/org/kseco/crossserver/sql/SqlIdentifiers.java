package org.kseco.crossserver.sql;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Strict validation and quoting for identifiers that cannot be JDBC parameters. */
public final class SqlIdentifiers {
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    private SqlIdentifiers() {
    }

    public static String requireSimple(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        if (!SEGMENT.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Unsafe SQL identifier: " + identifier);
        }
        return identifier;
    }

    public static String requireQualified(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        String[] segments = identifier.split("\\.", -1);
        if (segments.length == 0 || segments.length > 3) {
            throw new IllegalArgumentException("SQL identifier must contain one to three segments: " + identifier);
        }
        for (String segment : segments) {
            requireSimple(segment);
        }
        return identifier;
    }

    public static List<String> requireSimple(Collection<String> identifiers) {
        Objects.requireNonNull(identifiers, "identifiers");
        List<String> validated = new ArrayList<>(identifiers.size());
        for (String identifier : identifiers) {
            validated.add(requireSimple(identifier));
        }
        return List.copyOf(validated);
    }

    public static String quoteQualified(SqlDialect dialect, String identifier) {
        Objects.requireNonNull(dialect, "dialect");
        String validated = requireQualified(identifier);
        String[] segments = validated.split("\\.");
        StringBuilder quoted = new StringBuilder(validated.length() + (segments.length * 2));
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                quoted.append('.');
            }
            quoted.append(dialect.quoteSimpleIdentifier(segments[i]));
        }
        return quoted.toString();
    }
}
