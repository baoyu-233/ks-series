package org.kseco.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Portable tables used by compensation plans and once-per-player claims. */
public final class CompensationSchema {
    private CompensationSchema() {
    }

    public static void initialize(Connection connection) throws SQLException {
        DatabaseDialect dialect = BusinessSchemaDialect.detect(connection);
        String binary = BusinessSchemaDialect.binaryType(dialect);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS ks_compensation_plans ("
                    + "id VARCHAR(64) PRIMARY KEY,name VARCHAR(255) NOT NULL,item_material VARCHAR(128) NOT NULL,"
                    + "item_data " + binary + " NOT NULL,item_amount INTEGER NOT NULL DEFAULT 1,"
                    + "item_max_stack INTEGER NOT NULL DEFAULT 64,enabled INTEGER NOT NULL DEFAULT 1,"
                    + "starts_at BIGINT NOT NULL DEFAULT 0,ends_at BIGINT NOT NULL DEFAULT 0,"
                    + "created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_compensation_claims ("
                    + "plan_id VARCHAR(64) NOT NULL,player_uuid VARCHAR(64) NOT NULL,"
                    + "player_name VARCHAR(128) NOT NULL,claimed_at BIGINT NOT NULL,"
                    + "storage_id VARCHAR(64) NOT NULL,PRIMARY KEY (plan_id,player_uuid))");
        }
        BusinessSchemaDialect.createIndexIfMissing(
                connection, "idx_ks_comp_claim_player", "ks_compensation_claims", "player_uuid");
    }
}
