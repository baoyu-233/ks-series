package org.kseco.extra.bank;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoanPricingPolicyTest {

    private static final long NOW = 2_000_000_000L;

    @Test
    void goodRepaymentHistoryImprovesLimitAndRate() {
        LoanPricingPolicy.Config config = config();
        LoanPricingPolicy.Profile newBorrower = LoanPricingPolicy.buildProfile(
                new LoanPricingPolicy.Stats(0, 0, 0, 0, 0, 0, 0, NOW),
                config, 50_000, 1, 90);
        LoanPricingPolicy.Profile provenBorrower = LoanPricingPolicy.buildProfile(
                new LoanPricingPolicy.Stats(5, 0, 0, 0, 1, 0, NOW - 200L * 86_400L, NOW),
                config, 50_000, 1, 90);

        assertTrue(provenBorrower.score() > newBorrower.score());
        assertTrue(provenBorrower.maxPrincipal() > newBorrower.maxPrincipal());
        assertTrue(LoanPricingPolicy.quote(provenBorrower, config, 0.05, 10_000, 30, NOW).effectiveRate()
                < LoanPricingPolicy.quote(newBorrower, config, 0.05, 10_000, 30, NOW).effectiveRate());
    }

    @Test
    void overdueBorrowerIsBlockedAndCreditIsReduced() {
        LoanPricingPolicy.Profile profile = LoanPricingPolicy.buildProfile(
                new LoanPricingPolicy.Stats(1, 1, 1, 1, 2, 8_000, NOW - 120L * 86_400L, NOW),
                config(), 50_000, 1, 90);

        assertFalse(profile.eligible(config()));
        assertTrue(profile.score() < 580);
        assertTrue(profile.availableCredit() < profile.maxPrincipal());
        assertTrue(profile.nextSteps().contains("CLEAR_OVERDUE"));
    }

    @Test
    void longerTermsAddAVisibleCappedSpread() {
        LoanPricingPolicy.Config config = config();
        LoanPricingPolicy.Profile profile = LoanPricingPolicy.buildProfile(
                new LoanPricingPolicy.Stats(0, 0, 0, 0, 0, 0, 0, NOW),
                config, 50_000, 1, 90);
        LoanPricingPolicy.Quote shortQuote = LoanPricingPolicy.quote(profile, config, 0.05, 5_000, 30, NOW);
        LoanPricingPolicy.Quote longQuote = LoanPricingPolicy.quote(profile, config, 0.05, 5_000, 90, NOW);

        assertEquals(0, shortQuote.termSpread(), 0.000_001);
        assertEquals(0.01, longQuote.termSpread(), 0.000_001);
        assertEquals(shortQuote.totalDue() + 50, longQuote.totalDue(), 0.001);
    }

    private static LoanPricingPolicy.Config config() {
        return new LoanPricingPolicy.Config(
                650, 520, 30,
                760, 700, 640, 580,
                1.0, 0.85, 0.65, 0.40, 0.20,
                90, 90, 60, 30, 14,
                -0.005, 0, 0.01, 0.025, 0.05,
                0.005, 0.02, 0, 0.25, 120);
    }
}
