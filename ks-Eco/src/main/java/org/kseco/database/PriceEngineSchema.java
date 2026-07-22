package org.kseco.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Portable persistence schema owned by the dynamic price engine. */
public final class PriceEngineSchema {
    private PriceEngineSchema() {
    }

    public static void initialize(Connection connection) throws SQLException {
        DatabaseDialect dialect = BusinessSchemaDialect.detect(connection);
        String number = BusinessSchemaDialect.floatingPointType(dialect);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS ks_eco_trades ("
                    + "id VARCHAR(64) PRIMARY KEY,item_material VARCHAR(128),item_signature VARCHAR(512),"
                    + "quantity INTEGER,unit_price " + number + ",buyer_uuid VARCHAR(36),seller_uuid VARCHAR(36),"
                    + "timestamp BIGINT,trade_type VARCHAR(64))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS ks_eco_price_volatility ("
                    + "material VARCHAR(128) PRIMARY KEY,drift_value " + number + " DEFAULT 0,trend_bias " + number
                    + " DEFAULT 0,last_buy_price " + number + " DEFAULT 0,updated_at BIGINT)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS ks_eco_price_settings ("
                    + "config_key VARCHAR(128) PRIMARY KEY,config_value VARCHAR(2048) NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS ks_official_prices ("
                    + "material VARCHAR(128) PRIMARY KEY,buy_price " + number + " DEFAULT 0,sell_price " + number
                    + " DEFAULT 0,category VARCHAR(128) DEFAULT '',updated_at BIGINT DEFAULT 0)");
        }
        BusinessSchemaDialect.renameColumnIfPresent(connection, "ks_eco_price_settings", "key", "config_key");
        BusinessSchemaDialect.renameColumnIfPresent(connection, "ks_eco_price_settings", "value", "config_value");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_eco_trades", "is_test", "INTEGER DEFAULT 0");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_eco_price_volatility",
                "current_buy_price", number + " DEFAULT 0");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_eco_price_volatility",
                "market_average", number + " DEFAULT 0");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_eco_trades_material_time",
                "ks_eco_trades", "item_material", "timestamp");
    }
}
