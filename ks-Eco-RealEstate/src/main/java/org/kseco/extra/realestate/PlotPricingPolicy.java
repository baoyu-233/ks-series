package org.kseco.extra.realestate;

import java.util.Locale;

final class PlotPricingPolicy {

    static final String MODE_FLAT = "FLAT";
    static final String MODE_PER_BLOCK = "PER_BLOCK";

    private PlotPricingPolicy() {
    }

    static ZonePolicy policy(String pricingMode, double flatPrice, double pricePerBlock,
                             double minimumPrice, long maxPlotArea,
                             long playerSoftArea, long playerHardArea,
                             long enterpriseSoftArea, long enterpriseHardArea) {
        String mode = normalizeMode(pricingMode);
        requireMoney(flatPrice, "flatPrice");
        requireMoney(pricePerBlock, "pricePerBlock");
        requireMoney(minimumPrice, "minimumPrice");
        requireLimit(maxPlotArea, "maxPlotArea");
        validateLimits(playerSoftArea, playerHardArea, "player");
        validateLimits(enterpriseSoftArea, enterpriseHardArea, "enterprise");
        return new ZonePolicy(mode, flatPrice, pricePerBlock, minimumPrice, maxPlotArea,
                playerSoftArea, playerHardArea, enterpriseSoftArea, enterpriseHardArea);
    }

    static Quote quote(ZonePolicy policy, String ownerType, long currentlyHeldArea,
                       int x1, int z1, int x2, int z2) {
        if (policy == null) throw new IllegalArgumentException("policy is required");
        if (currentlyHeldArea < 0) throw new IllegalArgumentException("currentlyHeldArea must be non-negative");

        long area = area(x1, z1, x2, z2);
        if (policy.maxPlotArea() > 0 && area > policy.maxPlotArea()) {
            throw new PolicyViolation("plot area exceeds the configured maximum");
        }

        long heldAfter;
        try {
            heldAfter = Math.addExact(currentlyHeldArea, area);
        } catch (ArithmeticException exception) {
            throw new PolicyViolation("owner held area overflow", exception);
        }

        boolean player = "PLAYER".equalsIgnoreCase(ownerType);
        boolean enterprise = "ENTERPRISE".equalsIgnoreCase(ownerType);
        if (!player && !enterprise) throw new IllegalArgumentException("unsupported owner type");

        long softLimit = player ? policy.playerSoftArea() : policy.enterpriseSoftArea();
        long hardLimit = player ? policy.playerHardArea() : policy.enterpriseHardArea();
        if (hardLimit > 0 && heldAfter > hardLimit) {
            throw new PolicyViolation("owner held area exceeds the configured hard limit");
        }

        double basePrice = MODE_PER_BLOCK.equals(policy.pricingMode())
                ? policy.pricePerBlock() * area
                : policy.flatPrice();
        if (!Double.isFinite(basePrice) || basePrice < 0) {
            throw new PolicyViolation("calculated base price is invalid");
        }
        basePrice = Math.max(basePrice, policy.minimumPrice());

        boolean overSoftLimit = softLimit > 0 && heldAfter > softLimit;
        double multiplier = 1.0d;
        if (overSoftLimit) {
            multiplier += (double) (heldAfter - softLimit) / (double) softLimit;
        }
        double finalPrice = basePrice * multiplier;
        if (!Double.isFinite(finalPrice) || finalPrice < 0) {
            throw new PolicyViolation("calculated final price is invalid");
        }

        return new Quote(area, currentlyHeldArea, heldAfter, basePrice, multiplier, finalPrice,
                overSoftLimit, softLimit, hardLimit);
    }

    static long area(int x1, int z1, int x2, int z2) {
        long width = (long) Math.max(x1, x2) - Math.min(x1, x2) + 1L;
        long depth = (long) Math.max(z1, z2) - Math.min(z1, z2) + 1L;
        try {
            return Math.multiplyExact(width, depth);
        } catch (ArithmeticException exception) {
            throw new PolicyViolation("plot area overflow", exception);
        }
    }

    private static String normalizeMode(String pricingMode) {
        String mode = pricingMode == null ? MODE_FLAT : pricingMode.trim().toUpperCase(Locale.ROOT);
        if (!MODE_FLAT.equals(mode) && !MODE_PER_BLOCK.equals(mode)) {
            throw new IllegalArgumentException("unsupported pricing mode");
        }
        return mode;
    }

    private static void requireMoney(double value, String field) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(field + " must be finite and non-negative");
        }
    }

    private static void requireLimit(long value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must be non-negative");
    }

    private static void validateLimits(long soft, long hard, String prefix) {
        requireLimit(soft, prefix + "SoftArea");
        requireLimit(hard, prefix + "HardArea");
        if (soft > 0 && hard > 0 && soft > hard) {
            throw new IllegalArgumentException(prefix + " soft area cannot exceed hard area");
        }
    }

    record ZonePolicy(
            String pricingMode,
            double flatPrice,
            double pricePerBlock,
            double minimumPrice,
            long maxPlotArea,
            long playerSoftArea,
            long playerHardArea,
            long enterpriseSoftArea,
            long enterpriseHardArea
    ) {
    }

    record Quote(
            long area,
            long heldBefore,
            long heldAfter,
            double basePrice,
            double priceMultiplier,
            double finalPrice,
            boolean overSoftLimit,
            long softLimit,
            long hardLimit
    ) {
    }

    static final class PolicyViolation extends IllegalArgumentException {
        PolicyViolation(String message) {
            super(message);
        }

        PolicyViolation(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
