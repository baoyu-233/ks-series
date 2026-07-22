package org.kseco.extra.politic;

import org.kseco.database.BusinessSchemaDialect;
import org.kseco.database.PortableSqlMutation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

final class ProposalElectorateStore {

    private ProposalElectorateStore() {}

    static void ensureSchema(Connection conn) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_politic_electorate_stages (
                        proposal_id VARCHAR(64) NOT NULL,
                        vote_stage VARCHAR(32) NOT NULL,
                        snapshotted_at INTEGER NOT NULL,
                        PRIMARY KEY (proposal_id, vote_stage)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_politic_electorate_members (
                        proposal_id VARCHAR(64) NOT NULL,
                        vote_stage VARCHAR(32) NOT NULL,
                        voter_uuid VARCHAR(64) NOT NULL,
                        voter_office VARCHAR(32) NOT NULL,
                        snapshotted_at INTEGER NOT NULL,
                        PRIMARY KEY (proposal_id, vote_stage, voter_uuid)
                    )
                    """);
        }
        BusinessSchemaDialect.createIndexIfMissing(conn, "idx_politic_electorate_member_stage",
                "ks_politic_electorate_members", "proposal_id", "vote_stage");
    }

    static void replaceSnapshot(Connection conn, String proposalId, String stage,
                                List<Elector> electors, long now) throws SQLException {
        PortableSqlMutation.upsert(conn,
                "UPDATE ks_politic_electorate_stages SET snapshotted_at=? WHERE proposal_id=? AND vote_stage=?",
                ps -> { ps.setLong(1, now); ps.setString(2, proposalId); ps.setString(3, stage); },
                "INSERT INTO ks_politic_electorate_stages (proposal_id,vote_stage,snapshotted_at) VALUES (?,?,?)",
                ps -> { ps.setString(1, proposalId); ps.setString(2, stage); ps.setLong(3, now); });
        try (PreparedStatement delete = conn.prepareStatement("""
                DELETE FROM ks_politic_electorate_members WHERE proposal_id=? AND vote_stage=?
                """)) {
            delete.setString(1, proposalId);
            delete.setString(2, stage);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = conn.prepareStatement("""
                INSERT INTO ks_politic_electorate_members
                    (proposal_id,vote_stage,voter_uuid,voter_office,snapshotted_at)
                VALUES (?,?,?,?,?)
                """)) {
            for (Elector elector : electors) {
                insert.setString(1, proposalId);
                insert.setString(2, stage);
                insert.setString(3, elector.voterUuid());
                insert.setString(4, elector.voterOffice());
                insert.setLong(5, now);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    static Snapshot loadSnapshot(Connection conn, String proposalId, String stage) throws SQLException {
        try (PreparedStatement marker = conn.prepareStatement("""
                SELECT snapshotted_at FROM ks_politic_electorate_stages
                WHERE proposal_id=? AND vote_stage=?
                """)) {
            marker.setString(1, proposalId);
            marker.setString(2, stage);
            try (ResultSet rs = marker.executeQuery()) {
                if (!rs.next()) return new Snapshot(false, 0, List.of());
                long snapshottedAt = rs.getLong(1);
                List<Elector> electors = new ArrayList<>();
                try (PreparedStatement members = conn.prepareStatement("""
                        SELECT voter_uuid,voter_office
                        FROM ks_politic_electorate_members
                        WHERE proposal_id=? AND vote_stage=?
                        ORDER BY voter_uuid
                        """)) {
                    members.setString(1, proposalId);
                    members.setString(2, stage);
                    try (ResultSet memberRows = members.executeQuery()) {
                        while (memberRows.next()) {
                            electors.add(new Elector(memberRows.getString(1), memberRows.getString(2)));
                        }
                    }
                }
                return new Snapshot(true, snapshottedAt, List.copyOf(electors));
            }
        }
    }

    record Elector(String voterUuid, String voterOffice) {}

    record Snapshot(boolean exists, long snapshottedAt, List<Elector> electors) {}
}
