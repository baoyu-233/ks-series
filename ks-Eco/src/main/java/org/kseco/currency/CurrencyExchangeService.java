package org.kseco.currency;

import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Applies configured one-way exchanges atomically to one account. */
public final class CurrencyExchangeService {
    private final CurrencyRegistry registry;
    private final CurrencyLedger ledger;
    private final Map<String, ExchangeRule> rules;

    public CurrencyExchangeService(
            CurrencyRegistry registry,
            CurrencyLedger ledger,
            Collection<ExchangeRule> rules
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        Map<String, ExchangeRule> indexed = new LinkedHashMap<>();
        for (ExchangeRule rule : Objects.requireNonNull(rules, "rules")) {
            if (indexed.putIfAbsent(rule.id(), rule) != null) {
                throw new IllegalArgumentException("Duplicate exchange rule: " + rule.id());
            }
        }
        this.rules = Map.copyOf(indexed);
    }

    public ExchangeQuote quote(String ruleId, Money sourceAmount) {
        return requireRule(ruleId).quote(registry, sourceAmount);
    }

    public ExchangeResult exchange(
            IdempotencyKey operationId,
            CurrencyAccount account,
            String ruleId,
            Money sourceAmount
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(account, "account");
        ExchangeRule rule = requireRule(ruleId);
        ExchangeQuote quote = rule.quote(registry, sourceAmount);
        validateQuote(rule, sourceAmount, quote);
        LedgerResult result = ledger.apply(new LedgerMutation(
                operationId,
                "CURRENCY_EXCHANGE",
                quote.ruleId(),
                List.of(
                        new LedgerPosting(account, quote.sourceAmount().negate(), quote.ruleId()),
                        new LedgerPosting(account, quote.targetAmount(), quote.ruleId()))));
        return new ExchangeResult(quote, result);
    }

    private static void validateQuote(ExchangeRule rule, Money requestedSource, ExchangeQuote quote) {
        if (!rule.id().equals(quote.ruleId()) || !requestedSource.equals(quote.sourceAmount())) {
            throw new IllegalStateException("Exchange quote does not match the requested rule and source amount");
        }
        if (!rule.sourceCurrency().equals(quote.sourceAmount().currency())
                || !rule.targetCurrency().equals(quote.grossTargetAmount().currency())
                || !rule.targetCurrency().equals(quote.fee().currency())
                || !rule.targetCurrency().equals(quote.targetAmount().currency())) {
            throw new IllegalStateException("Exchange quote contains an unexpected currency");
        }
        if (!quote.sourceAmount().isPositive()
                || !quote.grossTargetAmount().isPositive()
                || quote.fee().isNegative()
                || !quote.targetAmount().isPositive()) {
            throw new IllegalStateException("Exchange quote contains an invalid amount");
        }
        long splitTotal = Math.addExact(quote.fee().minorUnits(), quote.targetAmount().minorUnits());
        if (splitTotal != quote.grossTargetAmount().minorUnits()) {
            throw new IllegalStateException("Exchange fee split does not conserve gross target units");
        }
        if ((rule.feeBasisPoints() == 0) != (quote.fee().minorUnits() == 0L)) {
            throw new IllegalStateException("Exchange fee does not match the configured fee rate");
        }
    }

    private ExchangeRule requireRule(String ruleId) {
        Objects.requireNonNull(ruleId, "ruleId");
        ExchangeRule rule = rules.get(ruleId.trim().toUpperCase(Locale.ROOT));
        if (rule == null) throw new IllegalArgumentException("Unknown exchange rule: " + ruleId);
        return rule;
    }

    public record ExchangeResult(ExchangeQuote quote, LedgerResult ledgerResult) {
        public boolean success() {
            return ledgerResult.success();
        }
    }
}
