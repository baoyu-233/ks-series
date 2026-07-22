package org.kseco.extra.politic;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

final class ProposalPayloadValidationTest {

    @Test
    void centralBankGrantDoesNotRequireLoanFields() {
        assertNull(ProposalManager.validateProposalPayload("CB_INJECT", Map.of(
                "bankId", "BANK-1", "amount", 1000.0, "mode", "GRANT")));
    }

    @Test
    void jsonStyleIntegralLoanTermIsAccepted() {
        assertNull(ProposalManager.validateProposalPayload("CB_INJECT", Map.of(
                "bankId", "BANK-1", "amount", 1000.0, "mode", "LOAN",
                "interestRate", 0.05, "termDays", 30.0)));
        assertNotNull(ProposalManager.validateProposalPayload("CB_INJECT", Map.of(
                "bankId", "BANK-1", "amount", 1000.0, "mode", "LOAN",
                "interestRate", 0.05, "termDays", 30.5)));
    }

    @Test
    void jsonStyleIntegralZoneCoordinatesAreAccepted() {
        assertNull(ProposalManager.validateProposalPayload("RE_ZONE_ADMIN", Map.of(
                "action", "create", "name", "Industrial One", "world", "world",
                "type", "INDUSTRIAL", "x1", 0.0, "z1", 0.0,
                "x2", 32.0, "z2", 32.0, "basePrice", 5000.0)));
    }
}
