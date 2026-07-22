package org.kseco;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EnterpriseProjectSettlementPolicyTest {
    @Test
    void externalPlayerMoneyPathsRemainClosedUntilJournaled() {
        assertTrue(EnterpriseProjectSettlementPolicy.requiresExternalWallet("PLAYER", 1.0d, 0.0d));
        assertTrue(EnterpriseProjectSettlementPolicy.requiresExternalWallet("PLAYER", 0.0d, 0.1d));
        assertFalse(EnterpriseProjectSettlementPolicy.requiresExternalWallet("PLAYER", 0.0d, 0.0d));
        assertFalse(EnterpriseProjectSettlementPolicy.requiresExternalWallet("ENTERPRISE", 10_000.0d, 0.5d));
    }
}
