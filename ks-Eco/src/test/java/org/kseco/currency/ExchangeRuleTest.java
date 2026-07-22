package org.kseco.currency;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExchangeRuleTest {
    private static final CurrencyId SOURCE = CurrencyId.of("SOURCE");
    private static final CurrencyId TARGET = CurrencyId.of("TARGET");
    private static final CurrencyId ALTERNATIVE = CurrencyId.of("ALTERNATIVE");

    @Test
    void roundsFeesUpSoSplittingCannotReduceTheFee() {
        CurrencyRegistry registry = integerRegistry();
        ExchangeRule rule = rule(new BigDecimal("1"), 4_900, 1L, 1_000L);

        ExchangeQuote whole = rule.quote(registry, new Money(SOURCE, 100L));
        assertEquals(100L, whole.grossTargetAmount().minorUnits());
        assertEquals(49L, whole.fee().minorUnits());
        assertEquals(51L, whole.targetAmount().minorUnits());

        long splitFee = 0L;
        long splitNet = 0L;
        for (int part = 0; part < 50; part++) {
            ExchangeQuote split = rule.quote(registry, new Money(SOURCE, 2L));
            splitFee = Math.addExact(splitFee, split.fee().minorUnits());
            splitNet = Math.addExact(splitNet, split.targetAmount().minorUnits());
        }
        assertTrue(splitFee >= whole.fee().minorUnits());
        assertTrue(splitNet <= whole.targetAmount().minorUnits());
        assertThrows(IllegalArgumentException.class,
                () -> rule.quote(registry, new Money(SOURCE, 1L)));
    }

    @Test
    void conservesExactMinorUnitsAcrossGrossFeeAndNet() {
        CurrencyRegistry registry = decimalRegistry();
        ExchangeRule rule = rule(new BigDecimal("1.2345"), 333, 1L, 10_000L);

        ExchangeQuote quote = rule.quote(registry, new Money(SOURCE, 200L));

        assertEquals(new Money(TARGET, 247L), quote.grossTargetAmount());
        assertEquals(new Money(TARGET, 9L), quote.fee());
        assertEquals(new Money(TARGET, 238L), quote.targetAmount());
        assertEquals(
                quote.grossTargetAmount().minorUnits(),
                Math.addExact(quote.fee().minorUnits(), quote.targetAmount().minorUnits()));
    }

    @Test
    void zeroFeePreservesTheEntireRoundedGrossAmount() {
        CurrencyRegistry registry = decimalRegistry();
        ExchangeRule rule = rule(new BigDecimal("1.2345"), 0, 1L, 10_000L);

        ExchangeQuote quote = rule.quote(registry, new Money(SOURCE, 200L));

        assertEquals(0L, quote.fee().minorUnits());
        assertEquals(quote.grossTargetAmount(), quote.targetAmount());
    }

    @Test
    void rejectsZeroNegativeAndAlternativeCurrencyInputs() {
        CurrencyRegistry registry = integerRegistry();
        ExchangeRule rule = rule(BigDecimal.ONE, 100, 1L, 100L);

        assertThrows(IllegalArgumentException.class,
                () -> rule.quote(registry, new Money(SOURCE, 0L)));
        assertThrows(IllegalArgumentException.class,
                () -> rule.quote(registry, new Money(SOURCE, -1L)));
        assertThrows(IllegalArgumentException.class,
                () -> rule.quote(registry, new Money(ALTERNATIVE, 10L)));
    }

    @Test
    void rejectsInvalidRatesFeesLimitsAndRoundedAwayOutput() {
        assertThrows(IllegalArgumentException.class,
                () -> rule(BigDecimal.ZERO, 0, 1L, 100L));
        assertThrows(IllegalArgumentException.class,
                () -> rule(BigDecimal.ONE, -1, 1L, 100L));
        assertThrows(IllegalArgumentException.class,
                () -> rule(BigDecimal.ONE, 10_000, 1L, 100L));
        assertThrows(IllegalArgumentException.class,
                () -> rule(BigDecimal.ONE, 0, 0L, 100L));
        assertThrows(IllegalArgumentException.class,
                () -> rule(BigDecimal.ONE, 0, 2L, 1L));

        ExchangeRule tinyOutput = rule(new BigDecimal("0.001"), 0, 1L, 100L);
        assertThrows(IllegalArgumentException.class,
                () -> tinyOutput.quote(integerRegistry(), new Money(SOURCE, 1L)));
    }

    private static ExchangeRule rule(BigDecimal rate, int feeBasisPoints, long minimum, long maximum) {
        return new ExchangeRule(
                "source_to_target",
                SOURCE,
                TARGET,
                rate,
                feeBasisPoints,
                minimum,
                maximum,
                true);
    }

    private static CurrencyRegistry integerRegistry() {
        return registry(0, RoundingMode.HALF_UP);
    }

    private static CurrencyRegistry decimalRegistry() {
        return registry(2, RoundingMode.HALF_UP);
    }

    private static CurrencyRegistry registry(int scale, RoundingMode roundingMode) {
        return new CurrencyRegistry(SOURCE, List.of(
                definition(SOURCE, scale, roundingMode, CurrencyFeature.EXCHANGE_SOURCE),
                definition(TARGET, scale, roundingMode, CurrencyFeature.EXCHANGE_TARGET),
                definition(ALTERNATIVE, scale, roundingMode, CurrencyFeature.EXCHANGE_SOURCE)));
    }

    private static CurrencyDefinition definition(
            CurrencyId id,
            int scale,
            RoundingMode roundingMode,
            CurrencyFeature feature
    ) {
        return new CurrencyDefinition(
                id,
                id.value(),
                id.value(),
                scale,
                roundingMode,
                EnumSet.of(feature));
    }
}
