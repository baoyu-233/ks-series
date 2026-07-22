package org.kseco.currency;

public record ExchangeQuote(
        String ruleId,
        Money sourceAmount,
        Money grossTargetAmount,
        Money fee,
        Money targetAmount
) {}
