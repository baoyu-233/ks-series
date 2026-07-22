package org.kseco;

import org.junit.jupiter.api.Test;

import java.util.UUID;

/** Compatibility checks for legacy cash data and currency-aware DTOs. */
public final class CurrencyCompatibilityContractsTest {
    @Test
    void normalizesCurrencyIds() {
        check(ListingManager.normalizeCurrencyId(null).equals("CASH"), "null currency must default to CASH");
        check(ListingManager.normalizeCurrencyId("  ").equals("CASH"), "blank currency must default to CASH");
        check(ListingManager.normalizeCurrencyId(" gold_bar ").equals("GOLD_BAR"),
                "currency ids must be canonicalized");
        check(LimitedSaleManager.normalizeCurrencyId("event.token").equals("EVENT.TOKEN"),
                "limited-sale ids must follow the shared rule");
    }

    @Test
    void rejectsInvalidCurrencyIds() {
        expectFailure(() -> ListingManager.normalizeCurrencyId("1GOLD"));
        expectFailure(() -> ListingManager.normalizeCurrencyId("GOLD:BAR"));
        expectFailure(() -> ListingManager.normalizeCurrencyId("A".repeat(33)));
    }

    @Test
    void preservesLegacyDtoDefaults() {
        UUID seller = UUID.randomUUID();
        ListingManager.Listing listing = new ListingManager.Listing(
                "listing", seller, "seller", "STONE", "sig", new byte[0], 1, 10.0, 10.0,
                "SELL", null, 0, "SELL", 1L, "ITEM", null);
        check(listing.currencyId().equals("CASH"), "legacy listing constructor must default to CASH");

        LimitedSaleManager.SaleItem sale = new LimitedSaleManager.SaleItem(
                "sale", "sale", "STONE", "", null, 10.0, -1, 0, -1, true,
                0L, 0L, 1L, 1L, LimitedSaleManager.SALE_TYPE_ITEM, "", false, 0.0);
        check(sale.currencyId().equals("CASH"), "legacy sale constructor must default to CASH");
    }

    @Test
    void carriesCurrencyPaymentData() {
        UUID buyer = UUID.randomUUID();
        ListingManager.PurchaseReservation reservation = new ListingManager.PurchaseReservation(
                "storage", "listing", buyer, 3, "gold_bar", 12.5);
        check(reservation.currencyId().equals("GOLD_BAR"), "reservation currency must be canonical");
        check(Double.compare(reservation.amount(), 12.5) == 0, "reservation must retain exact payment amount");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected contract rejection.
        }
    }
}
