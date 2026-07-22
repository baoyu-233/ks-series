package org.kseco.extra.realestate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlotPricingPolicyTest {

    @Test
    void flatModePreservesLegacyPrice() {
        PlotPricingPolicy.ZonePolicy policy = PlotPricingPolicy.policy("FLAT", 1_000.0d, 99.0d,
                0.0d, 0L, 0L, 0L, 0L, 0L);

        PlotPricingPolicy.Quote quote = PlotPricingPolicy.quote(policy, "PLAYER", 0L,
                10, 20, 25, 35);

        assertEquals(256L, quote.area());
        assertEquals(1_000.0d, quote.finalPrice());
        assertFalse(quote.overSoftLimit());
    }

    @Test
    void perBlockModeUsesInclusiveAreaAndMinimumPrice() {
        PlotPricingPolicy.ZonePolicy policy = PlotPricingPolicy.policy("per_block", 0.0d, 2.5d,
                100.0d, 0L, 0L, 0L, 0L, 0L);

        PlotPricingPolicy.Quote quote = PlotPricingPolicy.quote(policy, "PLAYER", 0L,
                4, 8, 1, 6);

        assertEquals(12L, quote.area());
        assertEquals(100.0d, quote.basePrice());
        assertEquals(100.0d, quote.finalPrice());
    }

    @Test
    void softLimitAddsProgressiveSurchargeWithoutRejectingPurchase() {
        PlotPricingPolicy.ZonePolicy policy = PlotPricingPolicy.policy("PER_BLOCK", 0.0d, 10.0d,
                0.0d, 0L, 100L, 200L, 0L, 0L);

        PlotPricingPolicy.Quote quote = PlotPricingPolicy.quote(policy, "PLAYER", 90L,
                0, 0, 4, 3);

        assertEquals(20L, quote.area());
        assertEquals(110L, quote.heldAfter());
        assertEquals(1.1d, quote.priceMultiplier(), 0.000_000_1d);
        assertEquals(220.0d, quote.finalPrice(), 0.000_000_1d);
        assertTrue(quote.overSoftLimit());
    }

    @Test
    void ownerTypeSelectsIndependentLimits() {
        PlotPricingPolicy.ZonePolicy policy = PlotPricingPolicy.policy("FLAT", 100.0d, 0.0d,
                0.0d, 0L, 0L, 100L, 0L, 1_000L);

        assertThrows(PlotPricingPolicy.PolicyViolation.class,
                () -> PlotPricingPolicy.quote(policy, "PLAYER", 90L, 0, 0, 3, 3));
        assertEquals(106L, PlotPricingPolicy.quote(policy, "ENTERPRISE", 90L,
                0, 0, 3, 3).heldAfter());
    }

    @Test
    void maximumPlotAreaAndHardLimitFailClosed() {
        PlotPricingPolicy.ZonePolicy policy = PlotPricingPolicy.policy("FLAT", 100.0d, 0.0d,
                0.0d, 64L, 80L, 100L, 80L, 100L);

        assertThrows(PlotPricingPolicy.PolicyViolation.class,
                () -> PlotPricingPolicy.quote(policy, "PLAYER", 0L, 0, 0, 8, 8));
        assertThrows(PlotPricingPolicy.PolicyViolation.class,
                () -> PlotPricingPolicy.quote(policy, "PLAYER", 90L, 0, 0, 3, 3));
    }

    @Test
    void coordinateAndPriceOverflowFailClosed() {
        PlotPricingPolicy.ZonePolicy normal = PlotPricingPolicy.policy("FLAT", 1.0d, 0.0d,
                0.0d, 0L, 0L, 0L, 0L, 0L);
        assertThrows(PlotPricingPolicy.PolicyViolation.class,
                () -> PlotPricingPolicy.quote(normal, "PLAYER", 0L,
                        Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));

        PlotPricingPolicy.ZonePolicy hugePrice = PlotPricingPolicy.policy("PER_BLOCK", 0.0d,
                Double.MAX_VALUE, 0.0d, 0L, 0L, 0L, 0L, 0L);
        assertThrows(PlotPricingPolicy.PolicyViolation.class,
                () -> PlotPricingPolicy.quote(hugePrice, "PLAYER", 0L, 0, 0, 1, 1));
    }

    @Test
    void invalidLimitOrderingIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PlotPricingPolicy.policy("FLAT", 1.0d, 0.0d, 0.0d,
                        0L, 101L, 100L, 0L, 0L));
    }
}
