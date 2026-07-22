package org.kseco.extra.tax;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Portable schema and compare-safe writes shared by the tax managers. */
final class TaxJdbcSupport {

    static final int ID_MAX_LENGTH = 64;
    static final int KEY_MAX_LENGTH = 128;

    private TaxJdbcSupport() {
    }

    static void ensureTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_tax_records (
                        id VARCHAR(64) PRIMARY KEY,
                        payer_uuid VARCHAR(36) NOT NULL,
                        payer_name VARCHAR(128),
                        category VARCHAR(128) NOT NULL,
                        base_amount DOUBLE PRECISION NOT NULL,
                        tax_rate DOUBLE PRECISION NOT NULL,
                        tax_amount DOUBLE PRECISION NOT NULL,
                        description TEXT,
                        collected_at BIGINT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_tax_penalties (
                        id VARCHAR(64) PRIMARY KEY,
                        target_uuid VARCHAR(36) NOT NULL,
                        target_name VARCHAR(128),
                        penalty_type VARCHAR(128) NOT NULL,
                        base_amount DOUBLE PRECISION NOT NULL,
                        penalty_rate DOUBLE PRECISION NOT NULL,
                        penalty_amount DOUBLE PRECISION NOT NULL,
                        reason TEXT,
                        paid INTEGER NOT NULL DEFAULT 0,
                        issued_at BIGINT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_tax_rates (
                        category VARCHAR(128) PRIMARY KEY,
                        rate DOUBLE PRECISION NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_tax_industry_rates (
                        category VARCHAR(128) NOT NULL,
                        industry VARCHAR(128) NOT NULL,
                        rate DOUBLE PRECISION NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY(category, industry)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_tax_brackets (
                        id VARCHAR(64) PRIMARY KEY,
                        industry VARCHAR(128) NOT NULL,
                        scope VARCHAR(128) NOT NULL DEFAULT 'ENTERPRISE_TAX',
                        profit_min DOUBLE PRECISION NOT NULL,
                        profit_max DOUBLE PRECISION NOT NULL,
                        rate DOUBLE PRECISION NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """);
        }
    }

    static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(
                connection.getCatalog(), null, table, column)) {
            if (columns.next()) return true;
        }
        try (ResultSet columns = connection.getMetaData().getColumns(
                connection.getCatalog(), null, table.toUpperCase(), column.toUpperCase())) {
            return columns.next();
        }
    }

    static void upsertGeneralRate(Connection connection, String category, double rate, long now)
            throws SQLException {
        boolean legacyIndustryColumn = hasColumn(connection, "ks_tax_rates", "industry");
        if (updateGeneralRate(connection, category, rate, now, legacyIndustryColumn) > 0) return;
        String sql = legacyIndustryColumn
                ? "INSERT INTO ks_tax_rates (category, industry, rate, updated_at) VALUES (?,?,?,?)"
                : "INSERT INTO ks_tax_rates (category, rate, updated_at) VALUES (?,?,?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, category);
            int offset = 0;
            if (legacyIndustryColumn) {
                insert.setString(2, "");
                offset = 1;
            }
            insert.setDouble(2 + offset, rate);
            insert.setLong(3 + offset, now);
            insert.executeUpdate();
        } catch (SQLException insertFailure) {
            if (updateGeneralRate(connection, category, rate, now, legacyIndustryColumn) == 0) {
                throw insertFailure;
            }
        }
    }

    private static int updateGeneralRate(Connection connection, String category, double rate, long now,
                                         boolean legacyIndustryColumn)
            throws SQLException {
        String sql = "UPDATE ks_tax_rates SET rate=?, updated_at=? WHERE category=?"
                + (legacyIndustryColumn ? " AND (industry IS NULL OR industry='')" : "");
        try (PreparedStatement update = connection.prepareStatement(
                sql)) {
            update.setDouble(1, rate);
            update.setLong(2, now);
            update.setString(3, category);
            return update.executeUpdate();
        }
    }

    static void upsertIndustryRate(Connection connection, String category, String industry,
                                   double rate, long now) throws SQLException {
        if (updateIndustryRate(connection, category, industry, rate, now) > 0) return;
        try {
            insertIndustryRate(connection, category, industry, rate, now);
        } catch (SQLException insertFailure) {
            if (updateIndustryRate(connection, category, industry, rate, now) == 0) {
                throw insertFailure;
            }
        }
    }

    static void insertIndustryRateIfAbsent(Connection connection, String category, String industry,
                                           double rate, long now) throws SQLException {
        if (industryRateExists(connection, category, industry)) return;
        try {
            insertIndustryRate(connection, category, industry, rate, now);
        } catch (SQLException insertFailure) {
            if (!industryRateExists(connection, category, industry)) throw insertFailure;
        }
    }

    private static void insertIndustryRate(Connection connection, String category, String industry,
                                           double rate, long now) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO ks_tax_industry_rates "
                        + "(category, industry, rate, updated_at) VALUES (?,?,?,?)")) {
            insert.setString(1, category);
            insert.setString(2, industry);
            insert.setDouble(3, rate);
            insert.setLong(4, now);
            insert.executeUpdate();
        }
    }

    private static int updateIndustryRate(Connection connection, String category, String industry,
                                          double rate, long now) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE ks_tax_industry_rates SET rate=?, updated_at=? "
                        + "WHERE category=? AND industry=?")) {
            update.setDouble(1, rate);
            update.setLong(2, now);
            update.setString(3, category);
            update.setString(4, industry);
            return update.executeUpdate();
        }
    }

    private static boolean industryRateExists(Connection connection, String category, String industry)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM ks_tax_industry_rates WHERE category=? AND industry=?")) {
            query.setString(1, category);
            query.setString(2, industry);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    static void upsertBracket(Connection connection, String id, String industry, String scope,
                              double profitMin, double profitMax, double rate, long now)
            throws SQLException {
        if (updateBracket(connection, id, industry, scope, profitMin, profitMax, rate, now) > 0) return;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO ks_tax_brackets "
                        + "(id, industry, scope, profit_min, profit_max, rate, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?)")) {
            insert.setString(1, id);
            insert.setString(2, industry);
            insert.setString(3, scope);
            insert.setDouble(4, profitMin);
            insert.setDouble(5, profitMax);
            insert.setDouble(6, rate);
            insert.setLong(7, now);
            insert.executeUpdate();
        } catch (SQLException insertFailure) {
            if (updateBracket(connection, id, industry, scope, profitMin, profitMax, rate, now) == 0) {
                throw insertFailure;
            }
        }
    }

    private static int updateBracket(Connection connection, String id, String industry, String scope,
                                     double profitMin, double profitMax, double rate, long now)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE ks_tax_brackets SET industry=?, scope=?, profit_min=?, "
                        + "profit_max=?, rate=?, updated_at=? WHERE id=?")) {
            update.setString(1, industry);
            update.setString(2, scope);
            update.setDouble(3, profitMin);
            update.setDouble(4, profitMax);
            update.setDouble(5, rate);
            update.setLong(6, now);
            update.setString(7, id);
            return update.executeUpdate();
        }
    }
}
