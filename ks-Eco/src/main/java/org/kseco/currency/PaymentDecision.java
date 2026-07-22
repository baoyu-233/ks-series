package org.kseco.currency;

public record PaymentDecision(Code code, Money price) {
    public enum Code {
        ACCEPTED,
        EXACT_CURRENCY_REQUIRED,
        CURRENCY_NOT_ACCEPTED,
        CURRENCY_NOT_SPENDABLE
    }

    public boolean accepted() {
        return code == Code.ACCEPTED;
    }
}
