package org.kseco.extra.tax;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaxValuePolicyTest {

    @Test
    void normalizesDecimalAndLegacyPercentageRates() {
        assertEquals(0.05d, TaxValuePolicy.normalizeRate(0.05d, 0.02d));
        assertEquals(0.05d, TaxValuePolicy.normalizeRate(5.0d, 0.02d));
        assertEquals(1.0d, TaxValuePolicy.normalizeRate(500.0d, 0.02d));
    }

    @Test
    void rejectsNonFiniteAndOutOfRangeAmounts() {
        assertFalse(TaxValuePolicy.isValidPositiveAmount(Double.NaN));
        assertFalse(TaxValuePolicy.isValidPositiveAmount(Double.POSITIVE_INFINITY));
        assertFalse(TaxValuePolicy.isValidPositiveAmount(0.0d));
        assertFalse(TaxValuePolicy.isValidPositiveAmount(TaxValuePolicy.MAX_AMOUNT + 1.0d));
        assertTrue(TaxValuePolicy.isValidPositiveAmount(TaxValuePolicy.MAX_AMOUNT));
    }

    @Test
    void taxCalculationFailsClosedAndStaysBounded() {
        assertEquals(0.0d, TaxValuePolicy.calculateTax(Double.NaN, 0.1d, 0.0d));
        assertEquals(0.0d, TaxValuePolicy.calculateTax(100.0d, Double.NaN, 0.0d));
        assertEquals(2.0d, TaxValuePolicy.calculateTax(100.0d, 0.01d, 2.0d));
        assertEquals(TaxValuePolicy.MAX_AMOUNT,
                TaxValuePolicy.calculateTax(TaxValuePolicy.MAX_AMOUNT, 1.0d,
                        Double.POSITIVE_INFINITY));
    }
}
