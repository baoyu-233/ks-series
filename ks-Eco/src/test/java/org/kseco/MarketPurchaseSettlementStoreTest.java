package org.kseco;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MarketPurchaseSettlementStoreTest {

    @Test
    void tracksKnownRecoveryStatesAndQuarantinesInterruptedWalletCalls() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            MarketPurchaseSettlementStore.initialize(connection);
            MarketPurchaseSettlementStore.Settlement first = settlement("first");
            MarketPurchaseSettlementStore.insertChargeClaim(connection, first, 1L);
            assertEquals(1, MarketPurchaseSettlementStore.markUnknownCallsForReview(connection, 2L));
            assertEquals(MarketPurchaseSettlementStore.REVIEW_REQUIRED,
                    MarketPurchaseSettlementStore.find(connection, first.id()).status());
            MarketPurchaseSettlementStore.Settlement review =
                    MarketPurchaseSettlementStore.find(connection, first.id());
            assertEquals(MarketPurchaseSettlementStore.BUYER_CHARGE_CLAIMED, review.reviewStage());
            assertFalse(MarketPurchaseSettlementStore.resolveReview(connection, first.id(), "wrong",
                    MarketPurchaseSettlementStore.COMPENSATED, "", 3L));

            MarketPurchaseSettlementStore.Settlement second = settlement("second");
            MarketPurchaseSettlementStore.insertChargeClaim(connection, second, 3L);
            assertTrue(MarketPurchaseSettlementStore.transition(connection, second.id(),
                    MarketPurchaseSettlementStore.BUYER_CHARGE_CLAIMED,
                    MarketPurchaseSettlementStore.BUYER_CHARGED, "", 4L));
            assertTrue(MarketPurchaseSettlementStore.transition(connection, second.id(),
                    MarketPurchaseSettlementStore.BUYER_CHARGED,
                    MarketPurchaseSettlementStore.REFUND_READY, "reservation failed", 5L));
            assertEquals(1, MarketPurchaseSettlementStore.open(connection).size());
        }
    }

    private static MarketPurchaseSettlementStore.Settlement settlement(String id) {
        return new MarketPurchaseSettlementStore.Settlement(id, "listing", null,
                UUID.randomUUID(), "buyer", UUID.randomUUID(), 2,
                20.0d, 0.05d, 1.0d, 21.0d,
                MarketPurchaseSettlementStore.BUYER_CHARGE_CLAIMED, "", "");
    }
}
