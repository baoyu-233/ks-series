package org.kseco.extra.bank;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerLoanCollateralStoreTest {

    @Test
    void reservesAttachesAndReleasesOwnedPlot() throws Exception {
        try (Connection connection = database()) {
            UUID owner = UUID.randomUUID();
            insertPlot(connection, "plot-1", owner, 20_000);

            var appraisal = PlayerLoanCollateralStore.reserve(connection, "request-1", "bank-1", owner,
                    "HOME", "PLOT", "plot-1", 15_000, 100);
            assertNotNull(appraisal);
            assertEquals(15_000, appraisal.maxLoan(), 0.001);
            assertEquals("MORTGAGED", scalar(connection,
                    "SELECT status FROM ks_re_plots WHERE id='plot-1'"));
            assertNull(PlayerLoanCollateralStore.reserve(connection, "request-2", "bank-1", owner,
                    "HOME", "PLOT", "plot-1", 1_000, 101));

            assertTrue(PlayerLoanCollateralStore.attachToLoan(connection, "request-1", "loan-1", 102));
            PlayerLoanCollateralStore.releaseLoan(connection, "loan-1", 103);
            assertEquals("RELEASED", scalar(connection,
                    "SELECT status FROM ks_bank_player_collateral WHERE asset_ref='plot-1'"));
            assertEquals("PURCHASED", scalar(connection,
                    "SELECT status FROM ks_re_plots WHERE id='plot-1'"));
        }
    }

    @Test
    void rejectsLoanAboveLtvAndForeclosesAfterGrace() throws Exception {
        try (Connection connection = database()) {
            UUID owner = UUID.randomUUID();
            insertPlot(connection, "plot-2", owner, 10_000);
            assertNull(PlayerLoanCollateralStore.reserve(connection, "request-x", "bank-1", owner,
                    "BUSINESS", "PLOT", "plot-2", 6_001, 100));

            assertNotNull(PlayerLoanCollateralStore.reserve(connection, "request-2", "bank-1", owner,
                    "BUSINESS", "PLOT", "plot-2", 6_000, 100));
            assertTrue(PlayerLoanCollateralStore.attachToLoan(connection, "request-2", "loan-2", 101));
            connection.createStatement().executeUpdate("INSERT INTO ks_bank_loans "
                    + "(id,bank_id,borrower_uuid,remaining,due_at,grace_until,status) VALUES "
                    + "('loan-2','bank-1','" + owner + "',6000,10,10,'OVERDUE')");

            PlayerLoanCollateralStore.maintainDefaults(connection, 10 + 3L * 86400L + 1);
            assertEquals("DEFAULTED", scalar(connection,
                    "SELECT status FROM ks_bank_loans WHERE id='loan-2'"));
            assertEquals("BANK", scalar(connection,
                    "SELECT owner_type FROM ks_re_plots WHERE id='plot-2'"));
            assertEquals("FORECLOSED", scalar(connection,
                    "SELECT status FROM ks_re_plots WHERE id='plot-2'"));
            assertEquals("SEIZED", scalar(connection,
                    "SELECT status FROM ks_bank_player_collateral WHERE loan_id='loan-2'"));
            assertEquals("OPEN", scalar(connection,
                    "SELECT status FROM ks_bank_collateral_auctions WHERE collateral_id LIKE 'PC-%'"));
        }
    }

    @Test
    void projectProductOnlyAcceptsPersonalAwardedContract() throws Exception {
        try (Connection connection = database()) {
            UUID owner = UUID.randomUUID();
            connection.createStatement().executeUpdate(
                    "INSERT INTO ks_ent_projects VALUES('project-1',20000,999999,'AWARDED')");
            connection.createStatement().executeUpdate("INSERT INTO ks_ent_bids "
                    + "VALUES('bid-1','project-1','PLAYER','" + owner + "','AWARDED')");
            var appraisal = PlayerLoanCollateralStore.appraise(connection, owner, "PROJECT",
                    "PROJECT_CONTRACT", "project-1");
            assertNotNull(appraisal);
            assertEquals(14_000, appraisal.maxLoan(), 0.001);
            assertNull(PlayerLoanCollateralStore.appraise(connection, UUID.randomUUID(), "PROJECT",
                    "PROJECT_CONTRACT", "project-1"));
            assertFalse(PlayerLoanCollateralStore.eligible(connection, owner, "PROJECT").isEmpty());
        }
    }

    @Test
    void rejectsPlotOrHouseWhileHouseHasActiveMarketListing() throws Exception {
        try (Connection connection = database()) {
            UUID owner = UUID.randomUUID();
            insertPlot(connection, "plot-listed", owner, 30_000);
            connection.createStatement().executeUpdate("INSERT INTO ks_re_houses VALUES"
                    + "('house-listed','plot-listed','PLAYER','" + owner + "')");
            connection.createStatement().executeUpdate("INSERT INTO ks_eco_active_property_listings VALUES"
                    + "('house-listed','listing-1')");

            assertNull(PlayerLoanCollateralStore.appraise(connection, owner, "HOME",
                    "PLOT", "plot-listed"));
            assertNull(PlayerLoanCollateralStore.appraise(connection, owner, "HOME",
                    "HOUSE", "house-listed"));
        }
    }

    private static Connection database() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:h2:mem:collateral_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        connection.createStatement().execute("CREATE TABLE ks_re_plots(id VARCHAR(64) PRIMARY KEY,"
                + "owner_type VARCHAR(32),owner_id VARCHAR(64),price DOUBLE,status VARCHAR(32))");
        connection.createStatement().execute("CREATE TABLE ks_re_houses(id VARCHAR(64) PRIMARY KEY,"
                + "plot_id VARCHAR(64),owner_type VARCHAR(32),owner_id VARCHAR(64))");
        connection.createStatement().execute("CREATE TABLE ks_eco_active_property_listings("
                + "house_id VARCHAR(64) PRIMARY KEY,listing_id VARCHAR(64) NOT NULL UNIQUE)");
        connection.createStatement().execute("CREATE TABLE ks_ent_projects(id VARCHAR(64) PRIMARY KEY,"
                + "budget DOUBLE,deadline BIGINT,status VARCHAR(32))");
        connection.createStatement().execute("CREATE TABLE ks_ent_bids(id VARCHAR(64) PRIMARY KEY,"
                + "project_id VARCHAR(64),bidder_type VARCHAR(32),bidder_uuid VARCHAR(36),status VARCHAR(32))");
        connection.createStatement().execute("CREATE TABLE ks_bank_collateral(id VARCHAR(64),asset_type VARCHAR(32),"
                + "asset_ref VARCHAR(512),status VARCHAR(32))");
        connection.createStatement().execute("CREATE TABLE ks_bank_loans(id VARCHAR(128) PRIMARY KEY,"
                + "bank_id VARCHAR(128),borrower_uuid VARCHAR(36),remaining DOUBLE,due_at BIGINT,grace_until BIGINT,status VARCHAR(32))");
        connection.createStatement().execute("CREATE TABLE ks_bank_collateral_auctions(id VARCHAR(128) PRIMARY KEY,"
                + "collateral_id VARCHAR(128),bank_id VARCHAR(128),asset_type VARCHAR(32),asset_ref VARCHAR(512),"
                + "starting_price DOUBLE,current_price DOUBLE,status VARCHAR(32),opens_at BIGINT,closes_at BIGINT)");
        PlayerLoanCollateralStore.initialize(connection);
        return connection;
    }

    private static void insertPlot(Connection connection, String id, UUID owner, double price) throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO ks_re_plots VALUES(?,'PLAYER',?,?,'PURCHASED')")) {
            statement.setString(1, id); statement.setString(2, owner.toString());
            statement.setDouble(3, price); statement.executeUpdate();
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (var rows = connection.createStatement().executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getString(1);
        }
    }
}
