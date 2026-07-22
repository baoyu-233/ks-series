package org.kseco;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PurchaseOrderReservationStoreTest {

    @Test
    void reservationStateTransitionsAreCompareAndSet() throws Exception {
        try (Connection connection = database()) {
            PurchaseOrderReservationStore.Reservation reservation = reservation();
            PurchaseOrderReservationStore.insert(connection, reservation, new byte[]{1}, null, 10);
            assertTrue(PurchaseOrderReservationStore.transition(connection, reservation.id(),
                    PurchaseOrderReservationStore.RESERVED,
                    PurchaseOrderReservationStore.SELLER_PAYOUT_CLAIMED, "", 20));
            assertEquals(PurchaseOrderReservationStore.SELLER_PAYOUT_CLAIMED,
                    PurchaseOrderReservationStore.find(connection, reservation.id()).status());
            assertEquals(false, PurchaseOrderReservationStore.transition(connection, reservation.id(),
                    PurchaseOrderReservationStore.RESERVED,
                    PurchaseOrderReservationStore.COMPENSATED, "stale", 30));
        }
    }

    @Test
    void restartMovesUnknownExternalClaimsToReview() throws Exception {
        try (Connection connection = database()) {
            PurchaseOrderReservationStore.Reservation reservation = reservation();
            PurchaseOrderReservationStore.insert(connection, reservation, new byte[]{1}, null, 10);
            PurchaseOrderReservationStore.transition(connection, reservation.id(),
                    PurchaseOrderReservationStore.RESERVED,
                    PurchaseOrderReservationStore.BUYER_CHARGE_CLAIMED, "", 20);
            assertEquals(1, PurchaseOrderReservationStore.markInterruptedClaimsForReview(connection, 30));
            assertEquals(PurchaseOrderReservationStore.REVIEW_REQUIRED,
                    PurchaseOrderReservationStore.find(connection, reservation.id()).status());
        }
    }

    private static PurchaseOrderReservationStore.Reservation reservation() {
        return new PurchaseOrderReservationStore.Reservation("R-1", "O-1", UUID.randomUUID(),
                UUID.randomUUID(), 2, 20, false, PurchaseOrderReservationStore.RESERVED, "");
    }

    private static Connection database() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ks_eco_purchase_order_pending_items (id TEXT,reservation_id TEXT)");
        }
        PurchaseOrderReservationStore.initialize(connection);
        return connection;
    }
}
