package org.kseco.extra.politic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProposalElectorateStoreTest {

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        ProposalElectorateStore.ensureSchema(connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    void snapshotKeepsTheStageElectorateStable() throws Exception {
        ProposalElectorateStore.replaceSnapshot(connection, "p1", "SENATE_VOTING", List.of(
                new ProposalElectorateStore.Elector("u1", "SENATOR"),
                new ProposalElectorateStore.Elector("u2", "CONSUL")), 100);

        var snapshot = ProposalElectorateStore.loadSnapshot(connection, "p1", "SENATE_VOTING");

        assertTrue(snapshot.exists());
        assertEquals(100, snapshot.snapshottedAt());
        assertEquals(List.of("u1", "u2"),
                snapshot.electors().stream().map(ProposalElectorateStore.Elector::voterUuid).toList());
    }

    @Test
    void emptySnapshotDoesNotLookLikeAMissingSnapshot() throws Exception {
        ProposalElectorateStore.replaceSnapshot(
                connection, "p2", "SENATE_OVERRIDE", List.of(), 200);

        var empty = ProposalElectorateStore.loadSnapshot(connection, "p2", "SENATE_OVERRIDE");
        var missing = ProposalElectorateStore.loadSnapshot(connection, "missing", "SENATE_OVERRIDE");

        assertTrue(empty.exists());
        assertTrue(empty.electors().isEmpty());
        assertFalse(missing.exists());
    }
}
