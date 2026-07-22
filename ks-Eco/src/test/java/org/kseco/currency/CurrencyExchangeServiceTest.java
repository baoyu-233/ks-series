package org.kseco.currency;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyExchangeServiceTest {
    private static final CurrencyId SOURCE = CurrencyId.of("SOURCE");
    private static final CurrencyId TARGET = CurrencyId.of("TARGET");
    private static final CurrencyId ALTERNATIVE = CurrencyId.of("ALTERNATIVE");
    private static final CurrencyAccount ACCOUNT = CurrencyAccount.system("exchange-test");

    @Test
    void postsExactRuleCurrenciesAndNetAmount() throws SQLException {
        InMemoryLedger ledger = new InMemoryLedger();
        ledger.setBalance(ACCOUNT, SOURCE, 1_000L);
        CurrencyExchangeService service = service(ledger);

        CurrencyExchangeService.ExchangeResult result = service.exchange(
                IdempotencyKey.of("exchange:exact"),
                ACCOUNT,
                "source_to_target",
                new Money(SOURCE, 100L));

        assertTrue(result.success());
        assertEquals(49L, result.quote().fee().minorUnits());
        assertEquals(51L, result.quote().targetAmount().minorUnits());
        assertEquals(900L, ledger.balance(ACCOUNT, SOURCE).minorUnits());
        assertEquals(51L, ledger.balance(ACCOUNT, TARGET).minorUnits());

        LedgerMutation mutation = ledger.lastMutation();
        assertEquals(2, mutation.postings().size());
        assertEquals(new Money(SOURCE, -100L), mutation.postings().get(0).delta());
        assertEquals(new Money(TARGET, 51L), mutation.postings().get(1).delta());
    }

    @Test
    void preservesLedgerIdempotencyForReplayAndConflict() throws SQLException {
        InMemoryLedger ledger = new InMemoryLedger();
        ledger.setBalance(ACCOUNT, SOURCE, 1_000L);
        CurrencyExchangeService service = service(ledger);
        IdempotencyKey operationId = IdempotencyKey.of("exchange:idempotent");

        CurrencyExchangeService.ExchangeResult first = service.exchange(
                operationId, ACCOUNT, "source_to_target", new Money(SOURCE, 100L));
        CurrencyExchangeService.ExchangeResult replay = service.exchange(
                operationId, ACCOUNT, "SOURCE_TO_TARGET", new Money(SOURCE, 100L));
        CurrencyExchangeService.ExchangeResult conflict = service.exchange(
                operationId, ACCOUNT, "source_to_target", new Money(SOURCE, 200L));

        assertTrue(first.success());
        assertFalse(first.ledgerResult().replayed());
        assertTrue(replay.success());
        assertTrue(replay.ledgerResult().replayed());
        assertEquals(LedgerResult.Outcome.IDEMPOTENCY_CONFLICT, conflict.ledgerResult().outcome());
        assertEquals(900L, ledger.balance(ACCOUNT, SOURCE).minorUnits());
        assertEquals(51L, ledger.balance(ACCOUNT, TARGET).minorUnits());
    }

    @Test
    void rejectsInvalidOrAlternativeInputBeforeCallingTheLedger() {
        InMemoryLedger ledger = new InMemoryLedger();
        CurrencyExchangeService service = service(ledger);

        assertThrows(IllegalArgumentException.class,
                () -> service.exchange(
                        IdempotencyKey.of("exchange:zero"),
                        ACCOUNT,
                        "source_to_target",
                        new Money(SOURCE, 0L)));
        assertThrows(IllegalArgumentException.class,
                () -> service.exchange(
                        IdempotencyKey.of("exchange:negative"),
                        ACCOUNT,
                        "source_to_target",
                        new Money(SOURCE, -1L)));
        assertThrows(IllegalArgumentException.class,
                () -> service.exchange(
                        IdempotencyKey.of("exchange:alternative"),
                        ACCOUNT,
                        "source_to_target",
                        new Money(ALTERNATIVE, 100L)));
        assertEquals(0, ledger.applyCalls());
    }

    private static CurrencyExchangeService service(InMemoryLedger ledger) {
        CurrencyRegistry registry = new CurrencyRegistry(SOURCE, List.of(
                definition(SOURCE, CurrencyFeature.EXCHANGE_SOURCE),
                definition(TARGET, CurrencyFeature.EXCHANGE_TARGET),
                definition(ALTERNATIVE, CurrencyFeature.EXCHANGE_SOURCE)));
        ExchangeRule rule = new ExchangeRule(
                "source_to_target",
                SOURCE,
                TARGET,
                BigDecimal.ONE,
                4_900,
                1L,
                1_000L,
                true);
        return new CurrencyExchangeService(registry, ledger, List.of(rule));
    }

    private static CurrencyDefinition definition(CurrencyId id, CurrencyFeature feature) {
        return new CurrencyDefinition(
                id,
                id.value(),
                id.value(),
                0,
                RoundingMode.HALF_UP,
                EnumSet.of(feature));
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
