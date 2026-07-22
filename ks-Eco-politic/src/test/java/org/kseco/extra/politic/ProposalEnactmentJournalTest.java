package org.kseco.extra.politic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ProposalEnactmentJournalTest {

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE ks_politic_proposals (
                        id TEXT PRIMARY KEY,
                        status TEXT NOT NULL
                    )
                    """);
            statement.execute("CREATE TABLE effect_counter (id TEXT PRIMARY KEY, value INTEGER NOT NULL)");
            statement.execute("INSERT INTO effect_counter (id,value) VALUES ('bank',0)");
        }
        ProposalEnactmentJournal.ensureSchema(connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    void appliedClaimCannotRunTheEffectTwice() throws Exception {
        insertProposal("p1", "APPROVED");
        String operationId = ProposalEnactmentJournal.operationId("p1");
        ProposalEnactmentJournal.prepare(connection, "p1", operationId, "CB_INJECT", 10);

        connection.setAutoCommit(false);
        var first = ProposalEnactmentJournal.claim(
                connection, "p1", operationId, "CB_INJECT", "claim-1", 11);
        assertEquals(ProposalEnactmentJournal.ClaimOutcome.CLAIMED, first.outcome());
        incrementEffect();
        ProposalEnactmentJournal.markApplied(connection, "p1", operationId, "claim-1", 12);
        connection.commit();

        var second = ProposalEnactmentJournal.claim(
                connection, "p1", operationId, "CB_INJECT", "claim-2", 13);
        assertEquals(ProposalEnactmentJournal.ClaimOutcome.APPLIED, second.outcome());
        connection.commit();
        connection.setAutoCommit(true);

        assertEquals(1, effectValue());
        assertEquals(ProposalEnactmentJournal.APPLIED,
                ProposalEnactmentJournal.read(connection, "p1").status());
        assertEquals(1, ProposalEnactmentJournal.read(connection, "p1").attemptCount());
    }

    @Test
    void rolledBackEffectCanBeRetriedFromFailedJournal() throws Exception {
        insertProposal("p2", "APPROVED");
        String operationId = ProposalEnactmentJournal.operationId("p2");
        ProposalEnactmentJournal.prepare(connection, "p2", operationId, "CB_INJECT", 20);

        connection.setAutoCommit(false);
        var first = ProposalEnactmentJournal.claim(
                connection, "p2", operationId, "CB_INJECT", "claim-1", 21);
        assertEquals(ProposalEnactmentJournal.ClaimOutcome.CLAIMED, first.outcome());
        incrementEffect();
        connection.rollback();
        connection.setAutoCommit(true);

        ProposalEnactmentJournal.recordFailure(
                connection, "p2", operationId, "CB_INJECT", "simulated failure", 22);
        assertEquals(0, effectValue());
        assertEquals(ProposalEnactmentJournal.FAILED,
                ProposalEnactmentJournal.read(connection, "p2").status());

        connection.setAutoCommit(false);
        var retry = ProposalEnactmentJournal.claim(
                connection, "p2", operationId, "CB_INJECT", "claim-2", 23);
        assertEquals(ProposalEnactmentJournal.ClaimOutcome.CLAIMED, retry.outcome());
        incrementEffect();
        ProposalEnactmentJournal.markApplied(connection, "p2", operationId, "claim-2", 24);
        connection.commit();
        connection.setAutoCommit(true);

        assertEquals(1, effectValue());
        assertEquals(ProposalEnactmentJournal.APPLIED,
                ProposalEnactmentJournal.read(connection, "p2").status());
    }

    @Test
    void lateFailureCannotOverwriteAppliedJournal() throws Exception {
        insertProposal("p3", "APPROVED");
        String operationId = ProposalEnactmentJournal.operationId("p3");
        ProposalEnactmentJournal.prepare(connection, "p3", operationId, "SET_TAX_RATE", 30);

        connection.setAutoCommit(false);
        ProposalEnactmentJournal.claim(
                connection, "p3", operationId, "SET_TAX_RATE", "claim-1", 31);
        ProposalEnactmentJournal.markApplied(connection, "p3", operationId, "claim-1", 32);
        connection.commit();
        connection.setAutoCommit(true);

        ProposalEnactmentJournal.recordFailure(
                connection, "p3", operationId, "SET_TAX_RATE", "late failure", 33);

        var row = ProposalEnactmentJournal.read(connection, "p3");
        assertEquals(ProposalEnactmentJournal.APPLIED, row.status());
        assertEquals("", row.errorText());
    }

    private void insertProposal(String id, String status) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO ks_politic_proposals (id,status) VALUES (?,?)")) {
            insert.setString(1, id);
            insert.setString(2, status);
            insert.executeUpdate();
        }
    }

    private void incrementEffect() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE effect_counter SET value=value+1 WHERE id='bank'");
        }
    }

    private int effectValue() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT value FROM effect_counter WHERE id='bank'")) {
            return result.next() ? result.getInt(1) : -1;
        }
    }
}
