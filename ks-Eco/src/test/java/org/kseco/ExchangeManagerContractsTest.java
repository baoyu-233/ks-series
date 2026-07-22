package org.kseco;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExchangeManagerContractsTest {
    @Test
    void rejectsInvalidRuleQuantitiesAndOverflowingBatches() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExchangeManager.RuleItem("STONE", 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ExchangeManager.RuleItem("STONE", 2305, null));
        assertThrows(IllegalArgumentException.class,
                () -> ExchangeManager.totalQuantity(2304, Integer.MAX_VALUE));
        assertEquals(4608, ExchangeManager.totalQuantity(2304, 2));
    }

    @Test
    void rejectsNonFiniteLimitedSalePrices() {
        assertFalse(LimitedSaleManager.isValidPrice(Double.NaN));
        assertFalse(LimitedSaleManager.isValidPrice(Double.POSITIVE_INFINITY));
        assertFalse(LimitedSaleManager.isValidPrice(Double.NEGATIVE_INFINITY));
        assertFalse(LimitedSaleManager.isValidPrice(0.0d));
        assertFalse(LimitedSaleManager.isValidPrice(1_000_000_000_000.01d));
        assertTrue(LimitedSaleManager.isValidPrice(1.0d));
        assertTrue(LimitedSaleManager.isValidPrice(1_000_000_000_000d));
    }
}
