package org.kseco.extra.bank;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CentralBankPolicyTest {

    @Test
    void rejectsNonFinitePolicyInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> CentralBankPolicy.clamp(Double.NaN, 0, 1, "rate"));
        assertThrows(IllegalArgumentException.class,
                () -> CentralBankPolicy.clamp(Double.POSITIVE_INFINITY, 0, 1, "rate"));
        assertFalse(CentralBankPolicy.validPositiveAmount(Double.NaN));
        assertFalse(CentralBankPolicy.validPositiveAmount(Double.POSITIVE_INFINITY));
        assertFalse(CentralBankPolicy.validPositiveAmount(1_000_000_000_001d));
    }

    @Test
    void clampsFinitePolicyInputsAndAcceptsPositiveLiquidity() {
        assertEquals(0, CentralBankPolicy.clamp(-2, 0, 1, "rate"));
        assertEquals(1, CentralBankPolicy.clamp(2, 0, 1, "rate"));
        assertTrue(CentralBankPolicy.validPositiveAmount(1));
        assertFalse(CentralBankPolicy.validPositiveAmount(0));
    }
}
