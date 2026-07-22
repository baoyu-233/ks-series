package org.kseco.extra.politic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ProposalVoteStoreTest {

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        ProposalElectorateStore.ensureSchema(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE ks_politic_votes (
                        id TEXT PRIMARY KEY,
                        proposal_id TEXT NOT NULL,
                        voter_uuid TEXT NOT NULL,
                        voter_name TEXT NOT NULL,
                        voter_office TEXT NOT NULL,
                        vote TEXT NOT NULL,
                        vote_stage TEXT NOT NULL,
                        cast_at INTEGER NOT NULL
                    )
                    """);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    void stageDetailsExcludeVotesOutsideElectorateSnapshot() throws Exception {
        ProposalElectorateStore.replaceSnapshot(connection, "p1", "SENATE_VOTING", List.of(
                new ProposalElectorateStore.Elector("eligible", "SENATOR")), 100);
        insertVote("v1", "p1", "eligible", "YES", "SENATE_VOTING", 101);
        insertVote("v2", "p1", "historical", "NO", "SENATE_VOTING", 102);

        List<Map<String, Object>> votes = ProposalVoteStore.readStageVotes(
                connection, "p1", "SENATE_VOTING");

        assertEquals(1, votes.size());
        assertEquals("eligible", votes.getFirst().get("voterUuid"));
    }

    @Test
    void playerHistoryOnlyIncludesStagesWherePlayerWasSnapshotted() throws Exception {
        UUID player = UUID.randomUUID();
        ProposalElectorateStore.replaceSnapshot(connection, "p1", "SENATE_VOTING", List.of(
                new ProposalElectorateStore.Elector(player.toString(), "SENATOR")), 100);
        ProposalElectorateStore.replaceSnapshot(connection, "p2", "SENATE_VOTING", List.of(
                new ProposalElectorateStore.Elector("other", "SENATOR")), 200);
        insertVote("v1", "p1", player.toString(), "YES", "SENATE_VOTING", 101);
        insertVote("v2", "p2", player.toString(), "NO", "SENATE_VOTING", 201);

        List<Map<String, Object>> votes = ProposalVoteStore.readPlayerVotes(connection, player);

        assertEquals(1, votes.size());
        assertEquals("p1", votes.getFirst().get("proposalId"));
    }

    private void insertVote(String id, String proposalId, String voterUuid, String vote,
                            String stage, long castAt) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO ks_politic_votes
                    (id,proposal_id,voter_uuid,voter_name,voter_office,vote,vote_stage,cast_at)
                VALUES (?,?,?,?,?,?,?,?)
                """)) {
            insert.setString(1, id);
            insert.setString(2, proposalId);
            insert.setString(3, voterUuid);
            insert.setString(4, voterUuid);
            insert.setString(5, "SENATOR");
            insert.setString(6, vote);
            insert.setString(7, stage);
            insert.setLong(8, castAt);
            insert.executeUpdate();
        }
    }
}
