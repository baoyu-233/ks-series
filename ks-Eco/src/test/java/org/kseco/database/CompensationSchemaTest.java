package org.kseco.database;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompensationSchemaTest {
    @Test
    void initializesSqliteSchemaIdempotently() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            exercise(connection);
        }
    }

    @Test
    void initializesMysqlCompatibleSchemaIdempotently() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:comp_mysql;MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            exercise(connection);
        }
    }

    @Test
    void initializesPostgresqlCompatibleSchemaIdempotently() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:comp_postgres;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")) {
            exercise(connection);
        }
    }

    private static void exercise(Connection connection) throws Exception {
        CompensationSchema.initialize(connection);
        CompensationSchema.initialize(connection);
        try (var plan = connection.prepareStatement(
                "INSERT INTO ks_compensation_plans "
                        + "(id,name,item_material,item_data,created_at,updated_at) VALUES (?,?,?,?,?,?)")) {
            plan.setString(1, "plan");
            plan.setString(2, "测试补偿");
            plan.setString(3, "STONE");
            plan.setBytes(4, new byte[]{1, 2, 3});
            plan.setLong(5, 1L);
            plan.setLong(6, 1L);
            assertEquals(1, plan.executeUpdate());
        }
        connection.createStatement().executeUpdate(
                "INSERT INTO ks_compensation_claims "
                        + "(plan_id,player_uuid,player_name,claimed_at,storage_id) "
                        + "VALUES ('plan','player','玩家',2,'storage')");
        try (var rows = connection.createStatement().executeQuery(
                "SELECT item_data FROM ks_compensation_plans WHERE id='plan'")) {
            assertTrue(rows.next());
            assertEquals(3, rows.getBytes(1).length);
        }
    }
}
