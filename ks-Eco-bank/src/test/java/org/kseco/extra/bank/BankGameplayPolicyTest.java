package org.kseco.extra.bank;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankGameplayPolicyTest {

    @Test
    void maturedTermDepositPaysTheFullFixedReturn() {
        BankGameplayManager.TermPayout payout = BankGameplayManager.calculateTermPayout(
                10_000, 0.03, 1.0, 1_000, 2_000, 2_000);

        assertTrue(payout.matured());
        assertEquals(300, payout.interest());
        assertEquals(10_300, payout.payout());
    }

    @Test
    void earlyRedemptionKeepsPrincipalAndAppliesPenaltyOnlyToAccruedInterest() {
        BankGameplayManager.TermPayout payout = BankGameplayManager.calculateTermPayout(
                10_000, 0.10, 0.60, 1_000, 2_000, 1_500);

        assertFalse(payout.matured());
        assertEquals(200, payout.interest());
        assertEquals(10_200, payout.payout());
    }

    @Test
    void fullEarlyPenaltyNeverConsumesPrincipal() {
        BankGameplayManager.TermPayout payout = BankGameplayManager.calculateTermPayout(
                10_000, 0.10, 1.0, 1_000, 2_000, 1_500);

        assertEquals(0, payout.interest());
        assertEquals(10_000, payout.payout());
    }

    @Test
    void rejectsInvalidTermDepositInputs() {
        assertThrows(IllegalArgumentException.class, () -> BankGameplayManager.calculateTermPayout(
                -1, 0.10, 0.5, 1_000, 2_000, 1_500));
        assertThrows(IllegalArgumentException.class, () -> BankGameplayManager.calculateTermPayout(
                100, 0.10, 1.5, 1_000, 2_000, 1_500));
    }
}
