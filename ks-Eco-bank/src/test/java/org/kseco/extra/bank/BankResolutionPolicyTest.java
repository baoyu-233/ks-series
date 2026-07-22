package org.kseco.extra.bank;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankResolutionPolicyTest {

    @Test
    void fullyProtectsCoveredDepositsAndHaircutsOnlyUninsuredBalance() {
        var covered = BankResolutionManager.calculateClaim(80_000, 100_000, 0.25);
        assertEquals(80_000, covered.insured(), 0.001);
        assertEquals(80_000, covered.payout(), 0.001);
        assertEquals(0, covered.haircut(), 0.001);

        var large = BankResolutionManager.calculateClaim(200_000, 100_000, 0.25);
        assertEquals(100_000, large.insured(), 0.001);
        assertEquals(100_000, large.uninsured(), 0.001);
        assertEquals(25_000, large.uninsuredRecovery(), 0.001);
        assertEquals(125_000, large.payout(), 0.001);
        assertEquals(75_000, large.haircut(), 0.001);
    }

    @Test
    void rejectsInvalidRecoveryRatio() {
        assertThrows(IllegalArgumentException.class,
                () -> BankResolutionManager.calculateClaim(100, 100, 1.01));
    }

    @Test
    void schemaInitializationIsIdempotent() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:resolution_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")) {
            BankResolutionManager.initialize(connection);
            BankResolutionManager.initialize(connection);
            try (var rows = connection.createStatement().executeQuery(
                    "SELECT balance,coverage_limit FROM ks_bank_insurance_fund WHERE id='DEFAULT'")) {
                assertTrue(rows.next());
                assertEquals(10_000_000, rows.getDouble(1), 0.001);
                assertEquals(100_000, rows.getDouble(2), 0.001);
            }
        }
    }
}
