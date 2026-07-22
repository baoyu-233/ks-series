package org.kseco;

import org.junit.jupiter.api.Test;
import org.kseco.database.DatabaseDialect;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlindBoxLimitedSalePortabilityTest {
    @Test
    void worksInMySqlCompatibilityMode() throws Exception {
        verify("jdbc:h2:mem:bb_ls_mysql;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                DatabaseDialect.MYSQL);
    }

    @Test
    void worksInPostgreSqlCompatibilityMode() throws Exception {
        verify("jdbc:h2:mem:bb_ls_postgres;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                DatabaseDialect.POSTGRESQL);
    }

    @Test
    void remainsCompatibleWithSqlite() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            verify(connection, DatabaseDialect.SQLITE);
        }
    }

    private static void verify(String url, DatabaseDialect dialect) throws Exception {
        try (Connection connection = DriverManager.getConnection(url)) {
            verify(connection, dialect);
        }
    }

    private static void verify(Connection connection, DatabaseDialect dialect) throws Exception {
        BlindBoxManager.initializeSchema(connection, dialect);
        BlindBoxManager.initializeSchema(connection, dialect);
        LimitedSaleManager.initializeSchema(connection, dialect);
        LimitedSaleManager.initializeSchema(connection, dialect);

        BlindBoxManager.upsertPity(connection, "player", "pool", 2, 10);
        BlindBoxManager.upsertPity(connection, "player", "pool", 5, 20);
        BlindBoxManager.upsertPityRarity(connection, "player", "pool", "EPIC", 3, 10);
        BlindBoxManager.upsertPityRarity(connection, "player", "pool", "EPIC", 1, 20);
        try (var row = connection.createStatement().executeQuery(
                "SELECT count_since_rare,updated_at FROM ks_bb_pity WHERE uuid='player' AND pool_id='pool'")) {
            assertTrue(row.next());
            assertEquals(5, row.getInt(1));
            assertEquals(20, row.getLong(2));
        }
        try (var row = connection.createStatement().executeQuery(
                "SELECT count_since_hit FROM ks_bb_pity_rarity WHERE uuid='player' AND pool_id='pool' AND rarity='EPIC'")) {
            assertTrue(row.next());
            assertEquals(1, row.getInt(1));
        }

        connection.createStatement().executeUpdate("""
                INSERT INTO ks_bb_log(uuid,pool_id,item_material,rarity,pulled_at)
                VALUES ('player','pool','STONE','COMMON',1)
                """);
        try (var insert = connection.prepareStatement("""
                INSERT INTO ks_limited_sale_items
                (id,name,item_material,item_data,price,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?)
                """)) {
            insert.setString(1, "sale");
            insert.setString(2, "sale");
            insert.setString(3, "STONE");
            insert.setBytes(4, new byte[]{1, 2});
            insert.setDouble(5, 10);
            insert.setLong(6, 1);
            insert.setLong(7, 1);
            insert.executeUpdate();
        }
        LimitedSaleManager.incrementPurchased(connection, "sale", "player", 2, 10);
        LimitedSaleManager.incrementPurchased(connection, "sale", "player", 3, 20);
        try (var row = connection.createStatement().executeQuery(
                "SELECT purchased,updated_at FROM ks_limited_sale_players WHERE sale_id='sale' AND player_uuid='player'")) {
            assertTrue(row.next());
            assertEquals(5, row.getInt(1));
            assertEquals(20, row.getLong(2));
        }

        connection.createStatement().executeUpdate("""
                INSERT INTO ks_limited_sale_log
                (sale_id,player_uuid,quantity,price_each,total_price,bought_at)
                VALUES ('sale','player',1,10,10,1)
                """);
        try (var row = connection.createStatement().executeQuery(
                "SELECT id FROM ks_limited_sale_log")) {
            assertTrue(row.next());
            assertTrue(row.getLong(1) > 0);
        }
    }
}
