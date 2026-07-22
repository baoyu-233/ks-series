package org.kseco.extra.bank;

final class CentralBankPolicy {

    private static final double MAX_LIQUIDITY_AMOUNT = 1_000_000_000_000d;

    private CentralBankPolicy() { }

    static double clamp(double value, double minimum, double maximum, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
        return Math.max(minimum, Math.min(maximum, value));
    }

    static boolean validPositiveAmount(double amount) {
        return Double.isFinite(amount) && amount > 0 && amount <= MAX_LIQUIDITY_AMOUNT;
    }
}
