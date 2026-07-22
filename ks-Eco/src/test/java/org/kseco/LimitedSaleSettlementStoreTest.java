package org.kseco;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LimitedSaleSettlementStoreTest {

    @Test
    void chargeAndDeliveryStatesUseCompareAndSet() throws Exception {
        try (Connection connection = database()) {
            LimitedSaleSettlementStore.Settlement settlement = settlement();
            LimitedSaleSettlementStore.insertReady(connection, settlement, 10);
            assertTrue(LimitedSaleSettlementStore.claimCharge(connection, settlement.id(), "VAULT", 20));
            assertTrue(LimitedSaleSettlementStore.transition(connection, settlement.id(),
                    LimitedSaleSettlementStore.CHARGE_CLAIMED,
                    LimitedSaleSettlementStore.CHARGED, "", 30));
            assertEquals(LimitedSaleSettlementStore.CHARGED,
                    LimitedSaleSettlementStore.find(connection, settlement.id()).status());
        }
    }

    @Test
    void interruptedDeliveryBecomesReviewRequired() throws Exception {
        try (Connection connection = database()) {
            LimitedSaleSettlementStore.Settlement settlement = settlement();
            LimitedSaleSettlementStore.insertReady(connection, settlement, 10);
            LimitedSaleSettlementStore.claimCharge(connection, settlement.id(), "BUILTIN", 20);
            LimitedSaleSettlementStore.transition(connection, settlement.id(),
                    LimitedSaleSettlementStore.CHARGE_CLAIMED,
                    LimitedSaleSettlementStore.DELIVERING, "", 30);
            assertEquals(1, LimitedSaleSettlementStore.markUnknownCallsForReview(connection, 40));
            assertEquals(LimitedSaleSettlementStore.REVIEW_REQUIRED,
                    LimitedSaleSettlementStore.find(connection, settlement.id()).status());
        }
    }

    private static LimitedSaleSettlementStore.Settlement settlement() {
        return new LimitedSaleSettlementStore.Settlement("LS-1", UUID.randomUUID(), "Alice",
                "sale", 2, false, 100, "", LimitedSaleSettlementStore.READY, "");
    }

    private static Connection database() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        LimitedSaleSettlementStore.initialize(connection);
        return connection;
    }
}
