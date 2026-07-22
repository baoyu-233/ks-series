package org.kseco.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Portable tables for exchange rules and major-order policy configuration. */
public final class EconomicFeatureSchema {
    private EconomicFeatureSchema() {
    }

    public static void initialize(Connection connection) throws SQLException {
        initializeExchange(connection);
        initializeMajorOrders(connection);
    }

    public static void initializeExchange(Connection connection) throws SQLException {
        DatabaseDialect dialect = BusinessSchemaDialect.detect(connection);
        String binary = BusinessSchemaDialect.binaryType(dialect);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS ks_eco_exchange_rules ("
                    + "id VARCHAR(64) PRIMARY KEY,input_material VARCHAR(128) NOT NULL,input_quantity INTEGER NOT NULL DEFAULT 1,"
                    + "input_item_data " + binary + ",output_material VARCHAR(128) NOT NULL,"
                    + "output_quantity INTEGER NOT NULL DEFAULT 1,output_item_data " + binary + ","
                    + "created_by VARCHAR(128),created_at BIGINT NOT NULL,enabled INTEGER DEFAULT 1)");
        }
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_eco_exchange_rules", "name", "VARCHAR(255)");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_eco_exchange_rules", "inputs_json", "TEXT");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_eco_exchange_rules", "outputs_json", "TEXT");
    }

    public static void initializeMajorOrders(Connection connection) throws SQLException {
        DatabaseDialect dialect = BusinessSchemaDialect.detect(connection);
        String number = BusinessSchemaDialect.floatingPointType(dialect);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS ks_major_orders ("
                    + "id VARCHAR(64) PRIMARY KEY,title VARCHAR(255) NOT NULL,description VARCHAR(2048) DEFAULT '',"
                    + "metric_type VARCHAR(64) NOT NULL DEFAULT 'MANUAL',target_value " + number
                    + " NOT NULL DEFAULT 1,manual_value " + number + " NOT NULL DEFAULT 0,status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',"
                    + "starts_at BIGINT NOT NULL DEFAULT 0,ends_at BIGINT NOT NULL DEFAULT 0,"
                    + "created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL)");
        }
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_major_orders", "policy_industry",
                "VARCHAR(64) NOT NULL DEFAULT 'ALL'");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_major_orders", "policy_purpose",
                "VARCHAR(64) NOT NULL DEFAULT 'ALL'");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_major_orders", "policy_loan_rate_multiplier",
                number + " NOT NULL DEFAULT 1.0");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_major_orders", "policy_reserve_delta",
                number + " NOT NULL DEFAULT 0.0");
    }
}
