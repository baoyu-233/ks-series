package org.kseco;

import java.sql.Connection;
import java.sql.SQLException;

/** Atomic deadline guards shared by project bidding entry points. */
public final class ProjectBiddingDeadlineStore {
    private ProjectBiddingDeadlineStore() { }

    public static boolean acceptsBid(long deadline, long now) {
        return deadline > now;
    }

    public static boolean acceptsAward(long deadline, long now) {
        return deadline <= now;
    }

    public static int insertBidIfOpen(
            Connection connection, String id, String projectId, String enterpriseId,
            String bidderUuid, String bidderType, double bidAmount, boolean consortium,
            String consortiumMembers, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO ks_ent_bids "
                        + "(id,project_id,enterprise_id,bidder_uuid,bidder_type,bid_amount,"
                        + "is_consortium,consortium_members,status,submitted_at) "
                        + "SELECT ?,?,?,?,?,?,?,?,'PENDING',? "
                        + "WHERE EXISTS (SELECT 1 FROM ks_ent_projects "
                        + "WHERE id=? AND status='OPEN' AND deadline>?)")) {
            statement.setString(1, id);
            statement.setString(2, projectId);
            statement.setString(3, enterpriseId == null ? "" : enterpriseId);
            statement.setString(4, bidderUuid);
            statement.setString(5, bidderType);
            statement.setDouble(6, bidAmount);
            statement.setInt(7, consortium ? 1 : 0);
            statement.setString(8, consortiumMembers == null ? "" : consortiumMembers);
            statement.setLong(9, now);
            statement.setString(10, projectId);
            statement.setLong(11, now);
            return statement.executeUpdate();
        }
    }

    public static int claimProjectForAward(
            Connection connection, String projectId, String nextStatus, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_ent_projects SET status=? "
                        + "WHERE id=? AND status='OPEN' AND deadline<=?")) {
            statement.setString(1, nextStatus);
            statement.setString(2, projectId);
            statement.setLong(3, now);
            return statement.executeUpdate();
        }
    }
}
