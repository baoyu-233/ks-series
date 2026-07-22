package org.kseco.crossserver.sql;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** SQL fragments shared by the supported JDBC backends. */
public enum SqlDialect {
    SQLITE('"'),
    MYSQL('`'),
    POSTGRESQL('"');

    private final char identifierQuote;

    SqlDialect(char identifierQuote) {
        this.identifierQuote = identifierQuote;
    }

    public static SqlDialect parse(String value) {
        Objects.requireNonNull(value, "value");
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "sqlite", "sqlite3" -> SQLITE;
            case "mysql", "mariadb" -> MYSQL;
            case "postgres", "postgresql", "pgsql" -> POSTGRESQL;
            default -> throw new IllegalArgumentException("Unsupported SQL dialect: " + value);
        };
    }

    public static SqlDialect fromJdbcUrl(String jdbcUrl) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        String normalized = jdbcUrl.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("jdbc:sqlite:")) {
            return SQLITE;
        }
        if (normalized.startsWith("jdbc:mysql:") || normalized.startsWith("jdbc:mariadb:")) {
            return MYSQL;
        }
        if (normalized.startsWith("jdbc:postgresql:")) {
            return POSTGRESQL;
        }
        throw new IllegalArgumentException("Unsupported JDBC URL: " + jdbcUrl);
    }

    public String currentTimestampExpression() {
        return this == MYSQL ? "CURRENT_TIMESTAMP(3)" : "CURRENT_TIMESTAMP";
    }

    /** Returns the database clock as an epoch-millisecond BIGINT-compatible expression. */
    public String currentEpochMillisExpression() {
        return switch (this) {
            case SQLITE -> "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)";
            case MYSQL -> "CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)";
            case POSTGRESQL -> "CAST(EXTRACT(EPOCH FROM clock_timestamp()) * 1000 AS BIGINT)";
        };
    }

    public String insertIgnore(String table, List<String> columns, List<String> conflictColumns) {
        ValidatedColumns validated = validate(table, columns, conflictColumns, List.of(), true);
        String prefix = this == SQLITE ? "INSERT OR IGNORE INTO " : "INSERT INTO ";
        String sql = prefix + validated.table() + " (" + join(validated.columns()) + ") VALUES ("
                + placeholders(validated.columns().size()) + ")";
        return switch (this) {
            case SQLITE -> sql;
            case POSTGRESQL -> sql + " ON CONFLICT (" + join(validated.conflictColumns()) + ") DO NOTHING";
            case MYSQL -> {
                String noOp = quoteSimpleIdentifier(validated.conflictColumns().getFirst());
                yield sql + " ON DUPLICATE KEY UPDATE " + noOp + " = " + noOp;
            }
        };
    }

    public String upsert(
            String table,
            List<String> columns,
            List<String> conflictColumns,
            List<String> updateColumns
    ) {
        ValidatedColumns validated = validate(table, columns, conflictColumns, updateColumns, false);
        String sql = "INSERT INTO " + validated.table() + " (" + join(validated.columns()) + ") VALUES ("
                + placeholders(validated.columns().size()) + ")";
        if (this == MYSQL) {
            String assignments = validated.updateColumns().stream()
                    .map(this::quoteSimpleIdentifier)
                    .map(column -> column + " = VALUES(" + column + ")")
                    .collect(Collectors.joining(", "));
            return sql + " ON DUPLICATE KEY UPDATE " + assignments;
        }
        String assignments = validated.updateColumns().stream()
                .map(this::quoteSimpleIdentifier)
                .map(column -> column + " = excluded." + column)
                .collect(Collectors.joining(", "));
        return sql + " ON CONFLICT (" + join(validated.conflictColumns()) + ") DO UPDATE SET " + assignments;
    }

    String quoteSimpleIdentifier(String identifier) {
        String validated = SqlIdentifiers.requireSimple(identifier);
        return identifierQuote + validated + identifierQuote;
    }

    private ValidatedColumns validate(
            String table,
            List<String> columns,
            List<String> conflictColumns,
            List<String> updateColumns,
            boolean updatesOptional
    ) {
        String quotedTable = SqlIdentifiers.quoteQualified(this, table);
        List<String> safeColumns = SqlIdentifiers.requireSimple(columns);
        List<String> safeConflictColumns = SqlIdentifiers.requireSimple(conflictColumns);
        List<String> safeUpdateColumns = SqlIdentifiers.requireSimple(updateColumns);
        if (safeColumns.isEmpty()) {
            throw new IllegalArgumentException("At least one insert column is required");
        }
        if (safeConflictColumns.isEmpty()) {
            throw new IllegalArgumentException("At least one conflict column is required");
        }
        if (!updatesOptional && safeUpdateColumns.isEmpty()) {
            throw new IllegalArgumentException("At least one update column is required");
        }
        Set<String> unique = new HashSet<>(safeColumns);
        if (unique.size() != safeColumns.size()) {
            throw new IllegalArgumentException("Insert columns must be unique");
        }
        if (!unique.containsAll(safeConflictColumns) || !unique.containsAll(safeUpdateColumns)) {
            throw new IllegalArgumentException("Conflict and update columns must also be insert columns");
        }
        return new ValidatedColumns(quotedTable, safeColumns, safeConflictColumns, safeUpdateColumns);
    }

    private String join(List<String> columns) {
        return columns.stream().map(this::quoteSimpleIdentifier).collect(Collectors.joining(", "));
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private record ValidatedColumns(
            String table,
            List<String> columns,
            List<String> conflictColumns,
            List<String> updateColumns
    ) {
    }
}
