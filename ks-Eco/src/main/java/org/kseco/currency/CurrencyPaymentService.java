package org.kseco.currency;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** Pure validation plus atomic ledger settlement for exact/alternative currency prices. */
public final class CurrencyPaymentService {
    private final CurrencyRegistry registry;
    private final CurrencyLedger ledger;

    public CurrencyPaymentService(CurrencyRegistry registry, CurrencyLedger ledger) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    public PaymentDecision validate(PaymentRequirement requirement, CurrencyId tenderCurrency) {
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(tenderCurrency, "tenderCurrency");
        Money price = requirement.priceFor(tenderCurrency).orElse(null);
        if (price == null) {
            return new PaymentDecision(
                    requirement.exclusive()
                            ? PaymentDecision.Code.EXACT_CURRENCY_REQUIRED
                            : PaymentDecision.Code.CURRENCY_NOT_ACCEPTED,
                    null);
        }
        if (!price.currency().equals(tenderCurrency) || !price.isPositive()) {
            throw new IllegalStateException("Payment requirement returned an invalid tender price");
        }
        CurrencyDefinition definition = registry.find(tenderCurrency).orElse(null);
        if (definition == null || !definition.supports(CurrencyFeature.DIRECT_PAYMENT)) {
            return new PaymentDecision(PaymentDecision.Code.CURRENCY_NOT_SPENDABLE, price);
        }
        return new PaymentDecision(PaymentDecision.Code.ACCEPTED, price);
    }

    public PaymentResult pay(
            IdempotencyKey operationId,
            CurrencyAccount payer,
            CurrencyAccount payee,
            PaymentRequirement requirement,
            CurrencyId tenderCurrency
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(payer, "payer");
        Objects.requireNonNull(payee, "payee");
        if (payer.equals(payee)) throw new IllegalArgumentException("Payer and payee must differ");
        PaymentDecision decision = validate(requirement, tenderCurrency);
        if (!decision.accepted()) return new PaymentResult(decision, null);
        Money price = decision.price();
        Money configuredPrice = requirement.priceFor(tenderCurrency)
                .orElseThrow(() -> new IllegalStateException("Accepted payment has no configured tender price"));
        if (!configuredPrice.equals(price)) {
            throw new IllegalStateException("Accepted payment price changed before settlement");
        }
        Money debit = price.negate();
        validateSettlement(tenderCurrency, debit, price);
        LedgerMutation mutation = new LedgerMutation(
                operationId,
                "PAYMENT",
                requirement.reference(),
                List.of(
                        new LedgerPosting(payer, debit, requirement.reference()),
                        new LedgerPosting(payee, price, requirement.reference())));
        return new PaymentResult(decision, ledger.apply(mutation));
    }

    private static void validateSettlement(CurrencyId tenderCurrency, Money debit, Money credit) {
        if (!tenderCurrency.equals(debit.currency()) || !tenderCurrency.equals(credit.currency())) {
            throw new IllegalStateException("Payment settlement contains an unexpected currency");
        }
        if (!debit.isNegative() || !credit.isPositive()) {
            throw new IllegalStateException("Payment settlement must contain one debit and one credit");
        }
        if (Math.addExact(debit.minorUnits(), credit.minorUnits()) != 0L) {
            throw new IllegalStateException("Payment settlement does not conserve exact minor units");
        }
    }

    public record PaymentResult(PaymentDecision decision, LedgerResult ledgerResult) {
        public boolean success() {
            return decision.accepted() && ledgerResult != null && ledgerResult.success();
        }
    }
}
