package org.kseco.currency;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentRequirementTest {
    private static final CurrencyId CASH = CurrencyId.of("CASH");
    private static final CurrencyId TOKEN = CurrencyId.of("TOKEN");

    @Test
    void exactRequiresOneStrictlyPositiveMinorUnitPrice() {
        PaymentRequirement exact = PaymentRequirement.exact("shop:item", new Money(CASH, 1L));

        assertTrue(exact.exclusive());
        assertEquals(CASH, exact.requiredCurrency());
        assertEquals(new Money(CASH, 1L), exact.priceFor(CASH).orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> PaymentRequirement.exact("shop:zero", new Money(CASH, 0L)));
        assertThrows(IllegalArgumentException.class,
                () -> PaymentRequirement.exact("shop:negative", new Money(CASH, -1L)));
    }

    @Test
    void alternativesRequireTwoDistinctCanonicalCurrencies() {
        PaymentRequirement alternatives = PaymentRequirement.oneOf(
                "shop:choice",
                List.of(new Money(CASH, 100L), new Money(TOKEN, 7L)));

        assertEquals(List.of(new Money(CASH, 100L), new Money(TOKEN, 7L)), alternatives.prices());
        assertThrows(IllegalArgumentException.class,
                () -> PaymentRequirement.oneOf("shop:single", List.of(new Money(CASH, 100L))));
        assertThrows(IllegalArgumentException.class,
                () -> PaymentRequirement.oneOf(
                        "shop:duplicate",
                        List.of(
                                new Money(CurrencyId.of("cash"), 100L),
                                new Money(CurrencyId.of(" CASH "), 101L))));
    }

    @Test
    void alternativesRejectEmptyAndNonPositivePrices() {
        assertThrows(IllegalArgumentException.class,
                () -> PaymentRequirement.oneOf("shop:empty", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> PaymentRequirement.oneOf(
                        "shop:zero",
                        List.of(new Money(CASH, 100L), new Money(TOKEN, 0L))));
        assertThrows(IllegalArgumentException.class,
                () -> PaymentRequirement.oneOf(
                        "shop:negative",
                        List.of(new Money(CASH, 100L), new Money(TOKEN, -1L))));
    }
}
