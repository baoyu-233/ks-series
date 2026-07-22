package org.kseco.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/** Database family detected from the active JDBC connection. */
public enum DatabaseDialect {
    SQLITE(false),
    MYSQL(true),
    MARIADB(true),
    POSTGRESQL(true),
    H2(false),
    UNKNOWN(false);

    private final boolean sharedDatabaseCapable;

    DatabaseDialect(boolean sharedDatabaseCapable) {
        this.sharedDatabaseCapable = sharedDatabaseCapable;
    }

    public boolean sharedDatabaseCapable() {
        return sharedDatabaseCapable;
    }

    public static DatabaseDialect detect(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        String normalized = product == null ? "" : product.toLowerCase(Locale.ROOT);
        if (normalized.contains("mariadb")) return MARIADB;
        if (normalized.contains("mysql")) return MYSQL;
        if (normalized.contains("postgresql")) return POSTGRESQL;
        if (normalized.contains("sqlite")) return SQLITE;
        if (normalized.equals("h2")) {
            String url = connection.getMetaData().getURL();
            String normalizedUrl = url == null ? "" : url.toLowerCase(Locale.ROOT);
            if (normalizedUrl.contains("mode=postgresql")) return POSTGRESQL;
            if (normalizedUrl.contains("mode=mariadb")) return MARIADB;
            if (normalizedUrl.contains("mode=mysql")) return MYSQL;
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME='MODE'")) {
                if (rows.next()) {
                    String mode = rows.getString(1);
                    String normalizedMode = mode == null ? "" : mode.toLowerCase(Locale.ROOT);
                    if (normalizedMode.contains("postgresql")) return POSTGRESQL;
                    if (normalizedMode.contains("mariadb")) return MARIADB;
                    if (normalizedMode.contains("mysql")) return MYSQL;
                }
            } catch (SQLException ignored) {
                // Older H2 versions may expose settings differently; plain H2 remains a safe fallback.
            }
            return H2;
        }
        return UNKNOWN;
    }
}
