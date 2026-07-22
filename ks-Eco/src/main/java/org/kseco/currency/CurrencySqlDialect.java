package org.kseco.currency;

import java.util.Locale;

public enum CurrencySqlDialect {
    SQLITE,
    MYSQL,
    MARIADB,
    POSTGRESQL;

    public static CurrencySqlDialect fromName(String value) {
        if (value == null) throw new IllegalArgumentException("Database dialect is required");
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "SQLITE" -> SQLITE;
            case "MYSQL" -> MYSQL;
            case "MARIADB" -> MARIADB;
            case "POSTGRES", "POSTGRESQL" -> POSTGRESQL;
            default -> throw new IllegalArgumentException("Unsupported currency database dialect: " + value);
        };
    }

    String insertOperationSql() {
        String columns = "(operation_id, request_hash, operation_type, reference_id, status, "
                + "result_code, server_id, created_at, updated_at)";
        String values = " VALUES (?,?,?,?,?,?,?,?,?)";
        return switch (this) {
            case SQLITE, POSTGRESQL -> "INSERT INTO ks_eco_currency_operations " + columns + values
                    + " ON CONFLICT (operation_id) DO NOTHING";
            case MYSQL, MARIADB -> "INSERT IGNORE INTO ks_eco_currency_operations " + columns + values;
        };
    }

    String insertBalanceSql() {
        String columns = "(account_type, account_id, currency_id, balance_minor, balance_version, updated_at)";
        String values = " VALUES (?,?,?,?,?,?)";
        return switch (this) {
            case SQLITE, POSTGRESQL -> "INSERT INTO ks_eco_currency_balances " + columns + values
                    + " ON CONFLICT (account_type, account_id, currency_id) DO NOTHING";
            case MYSQL, MARIADB -> "INSERT IGNORE INTO ks_eco_currency_balances " + columns + values;
        };
    }
}
