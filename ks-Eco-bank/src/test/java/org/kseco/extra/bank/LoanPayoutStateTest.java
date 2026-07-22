package org.kseco.extra.bank;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoanPayoutStateTest {

    @Test
    void uncertainStartupStatesRequireManualReconciliation() {
        assertEquals(LoanPayoutState.RECONCILE_REQUIRED,
                LoanPayoutState.startupRecovery(LoanPayoutState.PENDING));
        assertEquals(LoanPayoutState.RECONCILE_REQUIRED,
                LoanPayoutState.startupRecovery(LoanPayoutState.SETTLING));
        assertEquals(LoanPayoutState.ACTIVE,
                LoanPayoutState.startupRecovery(LoanPayoutState.ACTIVE));
    }
}
