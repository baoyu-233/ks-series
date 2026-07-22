package org.kseco;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectBiddingDeadlineStoreTest {
    @Test
    void bidInsertIsConditionedOnOpenStatusAndFutureDeadline() throws Exception {
        try (Connection connection = database()) {
            assertEquals(1, ProjectBiddingDeadlineStore.insertBidIfOpen(connection,
                    "before", "project", "enterprise", "player", "ENTERPRISE",
                    100.0d, false, "", 99L));
            assertEquals(0, ProjectBiddingDeadlineStore.insertBidIfOpen(connection,
                    "at", "project", "enterprise", "player", "ENTERPRISE",
                    100.0d, false, "", 100L));

            connection.createStatement().executeUpdate(
                    "UPDATE ks_ent_projects SET deadline=200,status='PENDING_DEPOSIT'");
            assertEquals(0, ProjectBiddingDeadlineStore.insertBidIfOpen(connection,
                    "closed", "project", "enterprise", "player", "ENTERPRISE",
                    100.0d, false, "", 150L));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM ks_ent_bids"));
        }
    }

    @Test
    void awardClaimIsConditionedOnClosedDeadline() throws Exception {
        try (Connection connection = database()) {
            assertEquals(0, ProjectBiddingDeadlineStore.claimProjectForAward(
                    connection, "project", "AWARD_SETTLING", 99L));
            assertEquals(1, ProjectBiddingDeadlineStore.claimProjectForAward(
                    connection, "project", "AWARD_SETTLING", 100L));
            assertEquals(0, ProjectBiddingDeadlineStore.claimProjectForAward(
                    connection, "project", "AWARDED", 101L));
        }
    }

    @Test
    void boundarySemanticsMatchSqlGuards() {
        assertTrue(ProjectBiddingDeadlineStore.acceptsBid(101L, 100L));
        assertFalse(ProjectBiddingDeadlineStore.acceptsBid(100L, 100L));
        assertFalse(ProjectBiddingDeadlineStore.acceptsAward(101L, 100L));
        assertTrue(ProjectBiddingDeadlineStore.acceptsAward(100L, 100L));
    }

    private static Connection database() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.createStatement().execute(
                "CREATE TABLE ks_ent_projects(id TEXT PRIMARY KEY,status TEXT,deadline INTEGER)");
        connection.createStatement().execute(
                "CREATE TABLE ks_ent_bids(id TEXT PRIMARY KEY,project_id TEXT,enterprise_id TEXT,"
                        + "bidder_uuid TEXT,bidder_type TEXT,bid_amount REAL,is_consortium INTEGER,"
                        + "consortium_members TEXT,status TEXT,submitted_at INTEGER)");
        connection.createStatement().execute(
                "INSERT INTO ks_ent_projects VALUES('project','OPEN',100)");
        return connection;
    }

    private static int count(Connection connection, String sql) throws Exception {
        try (var rows = connection.createStatement().executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}
