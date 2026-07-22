package org.kseco.extra.realestatedungeon;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonSchemaPortabilityTest {
    @Test
    void initializesSqliteIdempotently() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            exercise(connection);
        }
    }

    @Test
    void initializesMysqlCompatibleSchemaIdempotently() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:dungeon_mysql;MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            exercise(connection);
        }
    }

    @Test
    void initializesPostgresqlCompatibleSchemaIdempotently() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:dungeon_postgres;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")) {
            exercise(connection);
        }
    }

    private static void exercise(Connection connection) throws Exception {
        DungeonSchema.initializeBase(connection);
        DungeonSchema.initializeTicketSettlements(connection);
        DungeonSchema.initializeBase(connection);
        DungeonSchema.initializeTicketSettlements(connection);
        connection.createStatement().executeUpdate(
                "INSERT INTO ks_dungeon_templates "
                        + "(id,name,difficulty,ticket_price,created_at,schematic,require_property_key,reward_config) "
                        + "VALUES ('template','测试','NORMAL',500,1,'arena',1,'{}')");
        connection.createStatement().executeUpdate(
                "INSERT INTO ks_dungeon_log (instance_id,event_type,created_at) VALUES ('instance','START',1)");
        connection.createStatement().executeUpdate(
                "INSERT INTO ks_dungeon_ticket_settlements "
                        + "(instance_id,template_id,payer_uuid,amount,status,owner_server_id,owner_instance_id,created_at,updated_at) "
                        + "VALUES ('instance','template','player',500,'CHARGE_READY','server','node',1,1)");
        try (var rows = connection.createStatement().executeQuery(
                "SELECT COUNT(*) FROM ks_dungeon_log WHERE instance_id='instance'")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
    }
}
