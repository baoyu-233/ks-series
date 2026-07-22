package org.kseco.extra.realestate;

import org.kseco.database.BusinessSchemaDialect;
import org.kseco.database.DatabaseDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Portable schema boundary for the real-estate extra. */
final class RealEstateSchema {
    private RealEstateSchema() {}

    static void initialize(Connection connection) throws SQLException {
        DatabaseDialect dialect = BusinessSchemaDialect.detect(connection);
        String decimal = BusinessSchemaDialect.floatingPointType(dialect);
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS ks_re_zones (id VARCHAR(64) PRIMARY KEY, name VARCHAR(128) NOT NULL, world VARCHAR(128) NOT NULL, x1 INTEGER NOT NULL, z1 INTEGER NOT NULL, x2 INTEGER NOT NULL, z2 INTEGER NOT NULL, type VARCHAR(32) NOT NULL DEFAULT 'RESIDENTIAL', base_price " + decimal + " NOT NULL DEFAULT 1000, tax_rate " + decimal + " NOT NULL DEFAULT 0.05, status VARCHAR(32) NOT NULL DEFAULT 'STATE_OWNED', created_at BIGINT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS ks_re_plots (id VARCHAR(64) PRIMARY KEY, zone_id VARCHAR(64) NOT NULL, world VARCHAR(128) NOT NULL, x1 INTEGER NOT NULL, z1 INTEGER NOT NULL, x2 INTEGER NOT NULL, z2 INTEGER NOT NULL, owner_type VARCHAR(32) NOT NULL, owner_id VARCHAR(64) NOT NULL, price " + decimal + " NOT NULL, tax_rate " + decimal + " NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'PURCHASED', purchased_at BIGINT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS ks_re_plot_trust (plot_id VARCHAR(64) NOT NULL, trusted_uuid VARCHAR(64) NOT NULL, trusted_name VARCHAR(64) DEFAULT '', can_build INTEGER NOT NULL DEFAULT 1, can_container INTEGER NOT NULL DEFAULT 1, can_interact INTEGER NOT NULL DEFAULT 1, granted_at BIGINT NOT NULL, PRIMARY KEY (plot_id, trusted_uuid))");
            s.execute("CREATE TABLE IF NOT EXISTS ks_re_houses (id VARCHAR(64) PRIMARY KEY, plot_id VARCHAR(64) NOT NULL, zone_id VARCHAR(64) NOT NULL, world VARCHAR(128) NOT NULL, x1 INTEGER NOT NULL, y1 INTEGER NOT NULL, z1 INTEGER NOT NULL, x2 INTEGER NOT NULL, y2 INTEGER NOT NULL, z2 INTEGER NOT NULL, owner_type VARCHAR(32) NOT NULL, owner_id VARCHAR(64) NOT NULL, dungeon_template_id VARCHAR(128) DEFAULT NULL, tax_rate " + decimal + " NOT NULL DEFAULT 0.05, name VARCHAR(128) DEFAULT NULL, registered_at BIGINT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS ks_re_house_trust (house_id VARCHAR(64) NOT NULL, trusted_uuid VARCHAR(64) NOT NULL, trusted_name VARCHAR(64) DEFAULT '', can_build INTEGER NOT NULL DEFAULT 1, can_container INTEGER NOT NULL DEFAULT 1, can_interact INTEGER NOT NULL DEFAULT 1, granted_at BIGINT NOT NULL, PRIMARY KEY (house_id, trusted_uuid))");
            s.execute("CREATE TABLE IF NOT EXISTS ks_re_land_perk_config (perk_key VARCHAR(128) PRIMARY KEY, perk_value " + decimal + " NOT NULL, updated_at BIGINT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS ks_re_plot_perks (plot_id VARCHAR(64) PRIMARY KEY, agri_enabled INTEGER NOT NULL DEFAULT 0, industry_enabled INTEGER NOT NULL DEFAULT 0, agri_growth_interval_ticks INTEGER NOT NULL DEFAULT 0, agri_growth_steps INTEGER NOT NULL DEFAULT 0, agri_growth_samples INTEGER NOT NULL DEFAULT 0, agri_harvest_yield_bonus_chance " + decimal + ", agri_official_premium_pct " + decimal + ", industry_furnace_speed_pct " + decimal + ", industry_furnace_bonus_output_chance " + decimal + ", industry_bidding_reputation_bonus_pct " + decimal + ", updated_at BIGINT NOT NULL)");
        }
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_re_plots", "instance_id", "VARCHAR(128) DEFAULT NULL");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_re_plots", "property_function", "VARCHAR(32) NOT NULL DEFAULT 'RESIDENTIAL'");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_re_plots", "dungeon_template_id", "VARCHAR(128) DEFAULT NULL");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_re_zones", "max_plots", "INTEGER NOT NULL DEFAULT 0");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_re_zones", "dungeon_template_id", "VARCHAR(128) DEFAULT NULL");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_re_houses", "tax_rate", decimal + " NOT NULL DEFAULT 0.05");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_re_plot_perks", "agri_growth_samples", "INTEGER NOT NULL DEFAULT 0");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_re_plots_instance", "ks_re_plots", "instance_id");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_re_houses_plot", "ks_re_houses", "plot_id");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_re_houses_zone", "ks_re_houses", "zone_id");
    }
}
