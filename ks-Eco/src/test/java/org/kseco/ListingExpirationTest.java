package org.kseco;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ListingExpirationTest {

    @Test
    void expiredItemListingIsReturnedExactlyOnce() throws Exception {
        try (Connection connection = database()) {
            insertListing(connection, "expired", "ITEM", 5, 99);

            assertEquals(1, ListingManager.expireListings(connection, 100));
            assertEquals(0, ListingManager.expireListings(connection, 100));
            assertEquals("EXPIRED", scalar(connection,
                    "SELECT status FROM ks_eco_listings WHERE id='expired'"));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM ks_eco_storage"));
            assertEquals(5, number(connection, "SELECT quantity FROM ks_eco_storage"));
            assertEquals("LISTING_EXPIRED:expired", scalar(connection,
                    "SELECT source FROM ks_eco_storage"));
        }
    }

    @Test
    void expiredPropertyListingDoesNotCreateItemDelivery() throws Exception {
        try (Connection connection = database()) {
            insertListing(connection, "property", "PROPERTY", 1, 99);

            assertEquals(1, ListingManager.expireListings(connection, 100));
            assertEquals("EXPIRED", scalar(connection,
                    "SELECT status FROM ks_eco_listings WHERE id='property'"));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM ks_eco_storage"));
        }
    }

    private static Connection database() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ks_eco_listings (id TEXT PRIMARY KEY,seller_uuid TEXT,item_data BLOB,"
                    + "item_material TEXT,quantity INTEGER,listing_asset_type TEXT,status TEXT,expires_at INTEGER)");
            statement.execute("CREATE TABLE ks_eco_storage (id TEXT PRIMARY KEY,owner_uuid TEXT,item_data BLOB,"
                    + "item_material TEXT,quantity INTEGER,source TEXT,stored_at INTEGER)");
        }
        return connection;
    }

    private static void insertListing(Connection connection, String id, String assetType,
                                      int quantity, long expiresAt) throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO ks_eco_listings VALUES (?,?,?,?,?,?,?,?)")) {
            statement.setString(1, id);
            statement.setString(2, UUID.randomUUID().toString());
            statement.setBytes(3, new byte[]{1, 2, 3});
            statement.setString(4, "STONE");
            statement.setInt(5, quantity);
            statement.setString(6, assetType);
            statement.setString(7, "ACTIVE");
            statement.setLong(8, expiresAt);
            statement.executeUpdate();
        }
    }

    private static int number(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
