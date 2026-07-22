package org.kseco.currency;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** One-way configured conversion rule, for example GOLD_BAR to CASH. */
public record ExchangeRule(
        String id,
        CurrencyId sourceCurrency,
        CurrencyId targetCurrency,
        BigDecimal targetMajorPerSourceMajor,
        int feeBasisPoints,
        long minimumSourceMinor,
        long maximumSourceMinor,
        boolean enabled
) {
    private static final Pattern VALID_ID = Pattern.compile("[A-Z][A-Z0-9_.-]{0,63}");
    private static final BigInteger BASIS_POINT_DENOMINATOR = BigInteger.valueOf(10_000L);

    public ExchangeRule {
        Objects.requireNonNull(id, "id");
        id = id.trim().toUpperCase(Locale.ROOT);
        if (!VALID_ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid exchange rule id: " + id);
        Objects.requireNonNull(sourceCurrency, "sourceCurrency");
        Objects.requireNonNull(targetCurrency, "targetCurrency");
        if (sourceCurrency.equals(targetCurrency)) throw new IllegalArgumentException("Exchange currencies must differ");
        Objects.requireNonNull(targetMajorPerSourceMajor, "targetMajorPerSourceMajor");
        if (targetMajorPerSourceMajor.signum() <= 0) throw new IllegalArgumentException("Exchange rate must be positive");
        if (feeBasisPoints < 0 || feeBasisPoints >= 10_000) {
            throw new IllegalArgumentException("Exchange fee must be between 0 and 9999 basis points");
        }
        if (minimumSourceMinor <= 0 || maximumSourceMinor < minimumSourceMinor) {
            throw new IllegalArgumentException("Invalid exchange source limits");
        }
    }

    public ExchangeQuote quote(CurrencyRegistry registry, Money sourceAmount) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(sourceAmount, "sourceAmount");
        if (!enabled) throw new IllegalStateException("Exchange rule is disabled: " + id);
        if (!sourceCurrency.equals(sourceAmount.currency())) throw new IllegalArgumentException("Wrong source currency");
        if (!sourceAmount.isPositive()) throw new IllegalArgumentException("Exchange source amount must be positive");
        if (sourceAmount.minorUnits() < minimumSourceMinor || sourceAmount.minorUnits() > maximumSourceMinor) {
            throw new IllegalArgumentException("Exchange amount is outside configured limits");
        }
        CurrencyDefinition source = registry.require(sourceCurrency);
        CurrencyDefinition target = registry.require(targetCurrency);
        if (!source.supports(CurrencyFeature.EXCHANGE_SOURCE)
                || !target.supports(CurrencyFeature.EXCHANGE_TARGET)) {
            throw new IllegalStateException("Currency definitions do not allow this exchange direction");
        }
        BigDecimal grossMajor = source.toMajorUnits(sourceAmount.minorUnits())
                .multiply(targetMajorPerSourceMajor, MathContext.DECIMAL128);
        Money gross = target.money(grossMajor);
        if (!gross.isPositive()) throw new IllegalArgumentException("Exchange output rounds to zero");

        long feeMinor = roundedUpFeeMinor(gross.minorUnits());
        Money fee = new Money(targetCurrency, feeMinor);
        Money targetAmount = new Money(
                targetCurrency,
                Math.subtractExact(gross.minorUnits(), feeMinor));
        if (!targetAmount.isPositive()) throw new IllegalArgumentException("Exchange output rounds to zero");
        return new ExchangeQuote(id, sourceAmount, gross, fee, targetAmount);
    }

    private long roundedUpFeeMinor(long grossMinor) {
        if (feeBasisPoints == 0) return 0L;
        BigInteger numerator = BigInteger.valueOf(grossMinor)
                .multiply(BigInteger.valueOf(feeBasisPoints));
        return numerator.add(BASIS_POINT_DENOMINATOR.subtract(BigInteger.ONE))
                .divide(BASIS_POINT_DENOMINATOR)
                .longValueExact();
    }
}
