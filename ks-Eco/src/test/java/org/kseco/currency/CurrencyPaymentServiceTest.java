package org.kseco.currency;

import org.junit.jupiter.api.Test;

import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyPaymentServiceTest {
    private static final CurrencyId CASH = CurrencyId.of("CASH");
    private static final CurrencyId TOKEN = CurrencyId.of("TOKEN");
    private static final CurrencyId CREDIT = CurrencyId.of("CREDIT");
    private static final CurrencyAccount PAYER = CurrencyAccount.system("payer");
    private static final CurrencyAccount PAYEE = CurrencyAccount.system("payee");

    @Test
    void exclusivePriceNeverFallsBackToAnotherTender() {
        InMemoryLedger ledger = new InMemoryLedger();
        CurrencyPaymentService service = service(ledger, true, true);
        PaymentRequirement requirement = PaymentRequirement.exact(
                "shop:exclusive",
                new Money(TOKEN, 7L));

        PaymentDecision decision = service.validate(requirement, CASH);

        assertEquals(PaymentDecision.Code.EXACT_CURRENCY_REQUIRED, decision.code());
        assertNull(decision.price());
        assertEquals(0, ledger.applyCalls());
    }

    @Test
    void alternativePriceUsesOnlyTheExplicitTenderPrice() throws SQLException {
        InMemoryLedger ledger = new InMemoryLedger();
        ledger.setBalance(PAYER, CASH, 1_000L);
        ledger.setBalance(PAYER, TOKEN, 100L);
        CurrencyPaymentService service = service(ledger, true, true);
        PaymentRequirement requirement = alternatives();

        CurrencyPaymentService.PaymentResult result = service.pay(
                IdempotencyKey.of("payment:token"),
                PAYER,
                PAYEE,
                requirement,
                TOKEN);

        assertTrue(result.success());
        assertEquals(new Money(TOKEN, 7L), result.decision().price());
        assertEquals(1_000L, ledger.balance(PAYER, CASH).minorUnits());
        assertEquals(93L, ledger.balance(PAYER, TOKEN).minorUnits());
        assertEquals(7L, ledger.balance(PAYEE, TOKEN).minorUnits());
        assertEquals(0L, ledger.balance(PAYEE, CASH).minorUnits());
        LedgerMutation mutation = ledger.lastMutation();
        assertEquals(new Money(TOKEN, -7L), mutation.postings().get(0).delta());
        assertEquals(new Money(TOKEN, 7L), mutation.postings().get(1).delta());
    }

    @Test
    void nonSpendableOrUnknownTenderFailsClosedWithoutCashFallback() throws SQLException {
        InMemoryLedger ledger = new InMemoryLedger();
        ledger.setBalance(PAYER, CASH, 1_000L);
        ledger.setBalance(PAYER, TOKEN, 100L);
        CurrencyPaymentService service = service(ledger, true, false);

        PaymentDecision nonSpendable = service.validate(alternatives(), TOKEN);
        PaymentRequirement unknownAlternative = PaymentRequirement.oneOf(
                "shop:unknown",
                List.of(new Money(CASH, 100L), new Money(CREDIT, 1L)));
        PaymentDecision unknown = service.validate(unknownAlternative, CREDIT);

        assertEquals(PaymentDecision.Code.CURRENCY_NOT_SPENDABLE, nonSpendable.code());
        assertEquals(new Money(TOKEN, 7L), nonSpendable.price());
        assertEquals(PaymentDecision.Code.CURRENCY_NOT_SPENDABLE, unknown.code());
        assertEquals(new Money(CREDIT, 1L), unknown.price());
        CurrencyPaymentService.PaymentResult rejected = service.pay(
                IdempotencyKey.of("payment:no-fallback"),
                PAYER,
                PAYEE,
                alternatives(),
                TOKEN);
        assertFalse(rejected.success());
        assertNull(rejected.ledgerResult());
        assertEquals(0, ledger.applyCalls());
        assertEquals(1_000L, ledger.balance(PAYER, CASH).minorUnits());
    }

    @Test
    void cashAlsoFailsClosedWhenDirectPaymentIsDisabled() {
        InMemoryLedger ledger = new InMemoryLedger();
        CurrencyPaymentService service = service(ledger, false, true);
        PaymentRequirement requirement = PaymentRequirement.exact(
                "shop:cash-disabled",
                new Money(CASH, 100L));

        PaymentDecision decision = service.validate(requirement, CASH);

        assertEquals(PaymentDecision.Code.CURRENCY_NOT_SPENDABLE, decision.code());
        assertEquals(new Money(CASH, 100L), decision.price());
        assertEquals(0, ledger.applyCalls());
    }

    @Test
    void settlementIsExactAndAlternativeReplayCannotChangeTender() throws SQLException {
        InMemoryLedger ledger = new InMemoryLedger();
        ledger.setBalance(PAYER, CASH, 1_000L);
        ledger.setBalance(PAYER, TOKEN, 100L);
        CurrencyPaymentService service = service(ledger, true, true);
        PaymentRequirement requirement = alternatives();
        IdempotencyKey operationId = IdempotencyKey.of("payment:choice");

        CurrencyPaymentService.PaymentResult first = service.pay(
                operationId, PAYER, PAYEE, requirement, CASH);
        CurrencyPaymentService.PaymentResult replay = service.pay(
                operationId, PAYER, PAYEE, requirement, CASH);
        CurrencyPaymentService.PaymentResult changedTender = service.pay(
                operationId, PAYER, PAYEE, requirement, TOKEN);

        assertTrue(first.success());
        assertFalse(first.ledgerResult().replayed());
        assertTrue(replay.success());
        assertTrue(replay.ledgerResult().replayed());
        assertEquals(LedgerResult.Outcome.IDEMPOTENCY_CONFLICT, changedTender.ledgerResult().outcome());
        assertEquals(900L, ledger.balance(PAYER, CASH).minorUnits());
        assertEquals(100L, ledger.balance(PAYEE, CASH).minorUnits());
        assertEquals(100L, ledger.balance(PAYER, TOKEN).minorUnits());
        assertEquals(0L, ledger.balance(PAYEE, TOKEN).minorUnits());
    }

    @Test
    void rejectsSelfPaymentBeforeCallingLedger() {
        InMemoryLedger ledger = new InMemoryLedger();
        CurrencyPaymentService service = service(ledger, true, true);

        assertThrows(IllegalArgumentException.class,
                () -> service.pay(
                        IdempotencyKey.of("payment:self"),
                        PAYER,
                        PAYER,
                        PaymentRequirement.exact("shop:self", new Money(CASH, 1L)),
                        CASH));
        assertEquals(0, ledger.applyCalls());
    }

    private static PaymentRequirement alternatives() {
        return PaymentRequirement.oneOf(
                "shop:choice",
                List.of(new Money(CASH, 100L), new Money(TOKEN, 7L)));
    }

    private static CurrencyPaymentService service(
            InMemoryLedger ledger,
            boolean cashSpendable,
            boolean tokenSpendable
    ) {
        CurrencyRegistry registry = new CurrencyRegistry(CASH, List.of(
                definition(CASH, cashSpendable),
                definition(TOKEN, tokenSpendable)));
        return new CurrencyPaymentService(registry, ledger);
    }

    private static CurrencyDefinition definition(CurrencyId id, boolean spendable) {
        Set<CurrencyFeature> features = spendable
                ? EnumSet.of(CurrencyFeature.DIRECT_PAYMENT)
                : Set.of();
        return new CurrencyDefinition(id, id.value(), id.value(), 0, RoundingMode.HALF_UP, features);
    }

    private static final class InMemoryLedger implements CurrencyLedger {
        private final Map<BalanceKey, Long> balances = new LinkedHashMap<>();
        private final Map<IdempotencyKey, LedgerMutation> operations = new LinkedHashMap<>();
        private LedgerMutation lastMutation;
        private int applyCalls;

        @Override
        public void initializeSchema() {
        }

        @Override
        public Money balance(CurrencyAccount account, CurrencyId currency) {
            return new Money(currency, balances.getOrDefault(new BalanceKey(account, currency), 0L));
        }

        @Override
        public LedgerResult apply(LedgerMutation mutation) {
            applyCalls++;
            LedgerMutation existing = operations.get(mutation.operationId());
            if (existing != null) {
                if (!existing.equals(mutation)) return LedgerResult.idempotencyConflict();
                return LedgerResult.applied(true, moneySnapshot());
            }

            Map<BalanceKey, Long> next = new LinkedHashMap<>(balances);
            for (LedgerPosting posting : mutation.postings()) {
                BalanceKey key = posting.balanceKey();
                long updated = Math.addExact(next.getOrDefault(key, 0L), posting.delta().minorUnits());
                if (updated < 0L) return LedgerResult.rejected(LedgerResult.CODE_INSUFFICIENT_FUNDS, false);
                next.put(key, updated);
            }
            balances.clear();
            balances.putAll(next);
            operations.put(mutation.operationId(), mutation);
            lastMutation = mutation;
            return LedgerResult.applied(false, moneySnapshot());
        }

        void setBalance(CurrencyAccount account, CurrencyId currency, long minorUnits) {
            balances.put(new BalanceKey(account, currency), minorUnits);
        }

        LedgerMutation lastMutation() {
            return lastMutation;
        }

        int applyCalls() {
            return applyCalls;
        }

        private Map<BalanceKey, Money> moneySnapshot() {
            Map<BalanceKey, Money> snapshot = new LinkedHashMap<>();
            balances.forEach((key, value) -> snapshot.put(key, new Money(key.currency(), value)));
            return snapshot;
        }
    }
}
