package org.kseco.extra.realestatedungeon;

import org.kseco.database.BusinessSchemaDialect;
import org.kseco.database.DatabaseDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Portable schema boundary for dungeon lifecycle and ticket-settlement tables. */
final class DungeonSchema {
    private DungeonSchema() {
    }

    static void initializeBase(Connection connection) throws SQLException {
        DatabaseDialect dialect = BusinessSchemaDialect.detect(connection);
        String number = BusinessSchemaDialect.floatingPointType(dialect);
        String identity = BusinessSchemaDialect.identityPrimaryKey(dialect);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS ks_dungeon_templates ("
                    + "id VARCHAR(64) PRIMARY KEY,name VARCHAR(255) NOT NULL,difficulty VARCHAR(32) NOT NULL DEFAULT 'NORMAL',"
                    + "ticket_price " + number + " NOT NULL DEFAULT 500,min_players INTEGER NOT NULL DEFAULT 1,"
                    + "max_players INTEGER NOT NULL DEFAULT 4,time_limit_minutes INTEGER NOT NULL DEFAULT 60,"
                    + "monster_level INTEGER NOT NULL DEFAULT 10,description VARCHAR(2048) DEFAULT '',"
                    + "created_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_dungeon_instances ("
                    + "id VARCHAR(64) PRIMARY KEY,template_id VARCHAR(64) NOT NULL,grid_id VARCHAR(128) NOT NULL,"
                    + "status VARCHAR(32) NOT NULL DEFAULT 'WAITING',started_at BIGINT NOT NULL,"
                    + "expires_at BIGINT NOT NULL,owner_uuid VARCHAR(64) NOT NULL,created_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_dungeon_participants ("
                    + "instance_id VARCHAR(64) NOT NULL,player_uuid VARCHAR(64) NOT NULL,"
                    + "player_name VARCHAR(128) NOT NULL DEFAULT '',joined_at BIGINT NOT NULL,"
                    + "status VARCHAR(32) NOT NULL DEFAULT 'ALIVE',died_at BIGINT DEFAULT 0,"
                    + "revive_count INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(instance_id,player_uuid))");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_dungeon_revivals ("
                    + "id VARCHAR(64) PRIMARY KEY,instance_id VARCHAR(64) NOT NULL,player_uuid VARCHAR(64) NOT NULL,"
                    + "revive_count INTEGER NOT NULL,cost_paid " + number + " NOT NULL,formula_cost " + number
                    + " NOT NULL,revived_at BIGINT NOT NULL,return_status VARCHAR(32) NOT NULL DEFAULT 'RETURNED',"
                    + "last_error VARCHAR(2048) NOT NULL DEFAULT '')");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_dungeon_log (id " + identity
                    + ",instance_id VARCHAR(64) NOT NULL,event_type VARCHAR(64) NOT NULL,"
                    + "player_uuid VARCHAR(64) DEFAULT '',detail VARCHAR(2048) DEFAULT '',created_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_dungeon_reward_roster ("
                    + "instance_id VARCHAR(64) NOT NULL,player_uuid VARCHAR(64) NOT NULL,"
                    + "PRIMARY KEY(instance_id,player_uuid))");
        }
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_dungeon_templates", "schematic",
                "VARCHAR(255) DEFAULT ''");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_dungeon_templates", "require_property_key",
                "INTEGER NOT NULL DEFAULT 0");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_dungeon_templates", "reward_config",
                "TEXT");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_dungeon_instances", "instance_world_id",
                "VARCHAR(128) DEFAULT ''");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_dungeon_instances", "reward_status",
                "VARCHAR(32) DEFAULT 'NONE'");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_dungeon_revivals", "return_status",
                "VARCHAR(32) NOT NULL DEFAULT 'RETURNED'");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_dungeon_revivals", "last_error",
                "VARCHAR(2048) NOT NULL DEFAULT ''");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_di_status", "ks_dungeon_instances", "status");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_di_owner", "ks_dungeon_instances", "owner_uuid");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_dr_instance", "ks_dungeon_revivals", "instance_id");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_dl_instance", "ks_dungeon_log", "instance_id");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE ks_dungeon_instances SET reward_status='NONE' "
                    + "WHERE reward_status IS NULL OR reward_status=''");
        }
    }

    static void initializeTicketSettlements(Connection connection) throws SQLException {
        DatabaseDialect dialect = BusinessSchemaDialect.detect(connection);
        String number = BusinessSchemaDialect.floatingPointType(dialect);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS ks_dungeon_ticket_settlements ("
                    + "instance_id VARCHAR(64) PRIMARY KEY,template_id VARCHAR(64) NOT NULL,"
                    + "payer_uuid VARCHAR(64) NOT NULL,amount " + number + " NOT NULL,status VARCHAR(32) NOT NULL,"
                    + "owner_server_id VARCHAR(64) NOT NULL,owner_instance_id VARCHAR(96) NOT NULL,"
                    + "error_message VARCHAR(2048),created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL)");
        }
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_dungeon_ticket_owner_status",
                "ks_dungeon_ticket_settlements", "owner_server_id", "status", "updated_at");
    }
}
