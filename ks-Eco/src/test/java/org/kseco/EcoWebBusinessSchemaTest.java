package org.kseco;

import org.junit.jupiter.api.Test;
import org.kseco.database.DatabaseDialect;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcoWebBusinessSchemaTest {
    @Test
    void initializesAndReopensOnSqlite() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            EcoWebBusinessSchema.initialize(connection);
            EcoWebBusinessSchema.initialize(connection);
            connection.createStatement().executeUpdate("""
                    INSERT INTO ks_audit_log(action,player_uuid,created_at)
                    VALUES ('TEST','00000000-0000-0000-0000-000000000001',1)
                    """);
            try (var row = connection.createStatement().executeQuery("SELECT id FROM ks_audit_log")) {
                assertTrue(row.next());
                assertTrue(row.getLong(1) > 0);
            }
        }
    }

    @Test
    void initializesAndReopensInMySqlCompatibilityMode() throws Exception {
        verify("jdbc:h2:mem:eco_web_mysql;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                DatabaseDialect.MYSQL);
    }

    @Test
    void initializesAndReopensInPostgreSqlCompatibilityMode() throws Exception {
        verify("jdbc:h2:mem:eco_web_postgres;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                DatabaseDialect.POSTGRESQL);
    }

    private static void verify(String url, DatabaseDialect dialect) throws Exception {
        try (Connection connection = DriverManager.getConnection(url)) {
            EcoWebBusinessSchema.initialize(connection, dialect);
            EcoWebBusinessSchema.initialize(connection, dialect);

            try (var tables = connection.getMetaData().getTables(null, null, "ks_%", new String[]{"TABLE"})) {
                int count = 0;
                while (tables.next()) count++;
                assertTrue(count >= 38, "all Web-owned business tables must be present");
            }

            connection.createStatement().executeUpdate("""
                    INSERT INTO ks_bb_log(uuid,pool_id,item_material,rarity,pulled_at)
                    VALUES ('00000000-0000-0000-0000-000000000001','pool','STONE','COMMON',1)
                    """);
            try (var row = connection.createStatement().executeQuery("SELECT id FROM ks_bb_log")) {
                assertTrue(row.next());
                assertTrue(row.getLong(1) > 0);
            }

            connection.createStatement().executeUpdate("""
                    INSERT INTO ks_bb_loot(id,pool_id,item_material,item_data,created_at)
                    VALUES ('loot','pool','STONE',X'0102',1)
                    """);
            try (var row = connection.createStatement().executeQuery("SELECT item_data FROM ks_bb_loot")) {
                assertTrue(row.next());
                assertEquals(2, row.getBytes(1).length);
            }
        }
    }
}
