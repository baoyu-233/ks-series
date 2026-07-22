package org.kseco.extra.tax;

final class TaxValuePolicy {

    static final double MAX_AMOUNT = 1_000_000_000_000d;

    private TaxValuePolicy() {
    }

    static double normalizeRate(double rawRate, double fallbackRate) {
        double value = Double.isFinite(rawRate) && rawRate >= 0.0d
                ? rawRate
                : fallbackRate;
        if (!Double.isFinite(value) || value < 0.0d) value = 0.0d;
        if (value > 1.0d && value <= 100.0d) value /= 100.0d;
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    static boolean isValidPositiveAmount(double amount) {
        return Double.isFinite(amount) && amount > 0.0d && amount <= MAX_AMOUNT;
    }

    static double calculateTax(double baseAmount, double rawRate, double minimumTax) {
        if (!isValidPositiveAmount(baseAmount)) return 0.0d;

        double rate = normalizeRate(rawRate, 0.0d);
        if (rate <= 0.0d) return 0.0d;

        double calculated = baseAmount * rate;
        if (!Double.isFinite(calculated) || calculated <= 0.0d) return 0.0d;

        double minimum = Double.isFinite(minimumTax) && minimumTax > 0.0d
                ? minimumTax
                : 0.0d;
        return Math.min(MAX_AMOUNT, Math.max(calculated, minimum));
    }
}
