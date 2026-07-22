package org.kseco;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ListingManagerPropertyClaimTest {
    @TempDir
    Path tempDirectory;

    @Test
    void concurrentPropertyListingsAllowExactlyOneWinner() throws Exception {
        String jdbcUrl = sqliteUrl("concurrent.db");
        try (Connection connection = open(jdbcUrl)) {
            createListingsTable(connection);
            ListingManager.initializePropertyListingClaims(connection);
        }

        int contenders = 12;
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ListingManager.Listing>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < contenders; index++) {
                int sellerIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try (Connection connection = open(jdbcUrl)) {
                        return ListingManager.createPropertyListingAtomically(connection,
                                UUID.randomUUID(), "seller-" + sellerIndex, "house-1", 1000.0);
                    }
                }));
            }
            ready.await();
            start.countDown();
            int winners = 0;
            for (Future<ListingManager.Listing> future : futures) {
                if (future.get() != null) winners++;
            }
            assertEquals(1, winners);
        } finally {
            executor.shutdownNow();
        }

        try (Connection connection = open(jdbcUrl)) {
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM ks_eco_listings WHERE status='ACTIVE'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM ks_eco_active_property_listings"));
        }
    }

    @Test
    void initializationToleratesHistoricalDuplicatesAndStillBlocksNewListing() throws Exception {
        String jdbcUrl = sqliteUrl("legacy.db");
        try (Connection connection = open(jdbcUrl)) {
            createListingsTable(connection);
            insertLegacyProperty(connection, "legacy-a", "house-1", "ACTIVE");
            insertLegacyProperty(connection, "legacy-b", "house-1", "ACTIVE");

            ListingManager.initializePropertyListingClaims(connection);
            ListingManager.initializePropertyListingClaims(connection);

            assertEquals(2, count(connection, "SELECT COUNT(*) FROM ks_eco_listings WHERE status='ACTIVE'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM ks_eco_active_property_listings"));
            assertNull(ListingManager.createPropertyListingAtomically(connection,
                    UUID.randomUUID(), "new-seller", "house-1", 2000.0));
        }
    }

    @Test
    void completedListingReleasesStaleClaimOnNextCreate() throws Exception {
        String jdbcUrl = sqliteUrl("relist.db");
        try (Connection connection = open(jdbcUrl)) {
            createListingsTable(connection);
            ListingManager.initializePropertyListingClaims(connection);
            ListingManager.Listing first = ListingManager.createPropertyListingAtomically(connection,
                    UUID.randomUUID(), "first", "house-1", 1000.0);
            assertNotNull(first);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE ks_eco_listings SET status='FILLED' WHERE id=?")) {
                update.setString(1, first.id());
                update.executeUpdate();
            }

            ListingManager.Listing second = ListingManager.createPropertyListingAtomically(connection,
                    UUID.randomUUID(), "second", "house-1", 1500.0);
            assertNotNull(second);
            assertEquals(1, count(connection,
                    "SELECT COUNT(*) FROM ks_eco_listings WHERE asset_ref='house-1' AND status='ACTIVE'"));
            try (ResultSet rows = connection.createStatement().executeQuery(
                    "SELECT listing_id FROM ks_eco_active_property_listings WHERE house_id='house-1'")) {
                rows.next();
                assertEquals(second.id(), rows.getString(1));
            }
        }
    }

    @Test
    void mortgagedOrBankLockedHouseCannotBeListed() throws Exception {
        String jdbcUrl = sqliteUrl("mortgage.db");
        try (Connection connection = open(jdbcUrl); Statement statement = connection.createStatement()) {
            createListingsTable(connection);
            ListingManager.initializePropertyListingClaims(connection);
            statement.execute("CREATE TABLE ks_re_plots(id TEXT PRIMARY KEY,status TEXT)");
            statement.execute("CREATE TABLE ks_re_houses(id TEXT PRIMARY KEY,plot_id TEXT)");
            statement.execute("CREATE TABLE ks_bank_player_collateral(asset_type TEXT,asset_ref TEXT,status TEXT)");
            statement.execute("INSERT INTO ks_re_plots VALUES('plot-1','MORTGAGED')");
            statement.execute("INSERT INTO ks_re_houses VALUES('house-1','plot-1')");
            assertNull(ListingManager.createPropertyListingAtomically(connection,
                    UUID.randomUUID(), "seller", "house-1", 1000));

            statement.execute("UPDATE ks_re_plots SET status='PURCHASED' WHERE id='plot-1'");
            statement.execute("INSERT INTO ks_bank_player_collateral VALUES('HOUSE','house-1','LOCKED')");
            assertNull(ListingManager.createPropertyListingAtomically(connection,
                    UUID.randomUUID(), "seller", "house-1", 1000));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM ks_eco_listings"));
        }
    }

    private String sqliteUrl(String fileName) {
        return "jdbc:sqlite:" + tempDirectory.resolve(fileName).toAbsolutePath();
    }

    private static Connection open(String jdbcUrl) throws Exception {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=10000");
            statement.execute("PRAGMA journal_mode=WAL");
        }
        return connection;
    }

    private static void createListingsTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE ks_eco_listings (
                        id TEXT PRIMARY KEY,
                        seller_uuid TEXT NOT NULL,
                        seller_name TEXT NOT NULL,
                        item_data BLOB NOT NULL,
                        item_material TEXT NOT NULL,
                        item_signature TEXT NOT NULL,
                        quantity INTEGER NOT NULL,
                        unit_price REAL NOT NULL,
                        total_price REAL NOT NULL,
                        currency_id TEXT NOT NULL DEFAULT 'CASH',
                        listing_type TEXT NOT NULL,
                        listing_mode TEXT NOT NULL,
                        wanted_material TEXT,
                        wanted_quantity INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER,
                        status TEXT NOT NULL,
                        listing_asset_type TEXT,
                        asset_ref TEXT
                    )
                    """);
        }
    }

    private static void insertLegacyProperty(Connection connection, String id, String houseId, String status)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ks_eco_listings
                (id,seller_uuid,seller_name,item_data,item_material,item_signature,quantity,unit_price,total_price,
                 currency_id,listing_type,listing_mode,wanted_material,wanted_quantity,created_at,expires_at,status,
                 listing_asset_type,asset_ref)
                VALUES (?,?,?,?,'AIR',?,1,1000,1000,'CASH','SELL','SELL',NULL,0,1,NULL,?,'PROPERTY',?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, UUID.randomUUID().toString());
            statement.setString(3, "legacy");
            statement.setBytes(4, new byte[0]);
            statement.setString(5, "PROPERTY:" + houseId);
            statement.setString(6, status);
            statement.setString(7, houseId);
            statement.executeUpdate();
        }
    }

    private static int count(Connection connection, String sql) throws Exception {
        try (ResultSet rows = connection.createStatement().executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
