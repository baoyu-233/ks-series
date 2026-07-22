package org.kseco;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProjectWalletSettlementStoreTest {
    @Test
    void quarantinesInterruptedCallsAndKeepsKnownStatesRecoverable() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            ProjectWalletSettlementStore.initialize(connection);
            ProjectWalletSettlementStore.Settlement direct = settlement("direct",
                    ProjectWalletSettlementStore.DIRECT_AWARD,
                    ProjectWalletSettlementStore.PREPAYMENT_READY);
            ProjectWalletSettlementStore.insert(connection, direct, 1L);
            assertTrue(ProjectWalletSettlementStore.transition(connection, direct.id(),
                    ProjectWalletSettlementStore.PREPAYMENT_READY,
                    ProjectWalletSettlementStore.PREPAYMENT_CLAIMED, "", 2L));
            ProjectWalletSettlementStore.Settlement charge = settlement("charge",
                    ProjectWalletSettlementStore.DEPOSIT_AWARD,
                    ProjectWalletSettlementStore.DEPOSIT_CHARGE_CLAIMED);
            ProjectWalletSettlementStore.insert(connection, charge, 2L);

            assertEquals(2, ProjectWalletSettlementStore.markInterruptedClaimsForReview(connection, 3L));
            assertEquals(ProjectWalletSettlementStore.REVIEW_REQUIRED,
                    ProjectWalletSettlementStore.find(connection, direct.id()).status());
            assertEquals(ProjectWalletSettlementStore.PREPAYMENT_CLAIMED,
                    ProjectWalletSettlementStore.find(connection, direct.id()).reviewStage());
            assertEquals(ProjectWalletSettlementStore.DEPOSIT_CHARGE_CLAIMED,
                    ProjectWalletSettlementStore.find(connection, charge.id()).reviewStage());

            ProjectWalletSettlementStore.Settlement deposit = settlement("deposit",
                    ProjectWalletSettlementStore.DEPOSIT_AWARD,
                    ProjectWalletSettlementStore.DEPOSIT_HELD);
            ProjectWalletSettlementStore.insert(connection, deposit, 4L);
            assertEquals(1, ProjectWalletSettlementStore.recoverable(connection).size());
            assertEquals(2, ProjectWalletSettlementStore.reviewRequired(connection).size());
            ProjectWalletSettlementStore.Settlement review =
                    ProjectWalletSettlementStore.find(connection, direct.id());
            assertFalse(ProjectWalletSettlementStore.resolveReview(connection, review.id(), "wrong-stage",
                    ProjectWalletSettlementStore.PREPAYMENT_READY, "", 5L));
            assertTrue(ProjectWalletSettlementStore.resolveReview(connection, review.id(), review.reviewStage(),
                    ProjectWalletSettlementStore.PREPAYMENT_READY, "resolved", 6L));
            assertEquals(ProjectWalletSettlementStore.PREPAYMENT_READY,
                    ProjectWalletSettlementStore.find(connection, review.id()).status());
            assertEquals("", ProjectWalletSettlementStore.find(connection, review.id()).reviewStage());
            assertFalse(ProjectWalletSettlementStore.resolveReview(connection, review.id(), review.reviewStage(),
                    ProjectWalletSettlementStore.FINALIZED, "stale retry", 7L));
        }
    }

    private static ProjectWalletSettlementStore.Settlement settlement(String id, String kind, String status) {
        return new ProjectWalletSettlementStore.Settlement(id, kind, "project-" + id, "bid-" + id,
                UUID.randomUUID(), 100.0d, 500.0d, status, "", "");
    }
}
