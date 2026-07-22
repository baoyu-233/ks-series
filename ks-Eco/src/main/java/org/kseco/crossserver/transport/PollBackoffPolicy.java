package org.kseco.crossserver.transport;

/** Pure, injectable exponential backoff calculation for database polling. */
public record PollBackoffPolicy(
        long activityDelayMillis,
        long emptyInitialDelayMillis,
        long emptyMaxDelayMillis,
        long failureInitialDelayMillis,
        long failureMaxDelayMillis,
        double multiplier,
        double jitterRatio
) {
    public enum Outcome {
        ACTIVITY,
        EMPTY,
        FAILURE
    }

    public PollBackoffPolicy {
        if (activityDelayMillis < 0L || emptyInitialDelayMillis < 0L || failureInitialDelayMillis < 0L) {
            throw new IllegalArgumentException("poll delays must be non-negative");
        }
        if (emptyMaxDelayMillis < emptyInitialDelayMillis) {
            throw new IllegalArgumentException("emptyMaxDelayMillis must cover its initial delay");
        }
        if (failureMaxDelayMillis < failureInitialDelayMillis) {
            throw new IllegalArgumentException("failureMaxDelayMillis must cover its initial delay");
        }
        if (!Double.isFinite(multiplier) || multiplier < 1.0d) {
            throw new IllegalArgumentException("multiplier must be finite and at least 1");
        }
        if (!Double.isFinite(jitterRatio) || jitterRatio < 0.0d || jitterRatio > 1.0d) {
            throw new IllegalArgumentException("jitterRatio must be between 0 and 1");
        }
    }

    public static PollBackoffPolicy defaults() {
        return new PollBackoffPolicy(25L, 250L, 5_000L, 1_000L, 30_000L, 2.0d, 0.20d);
    }

    /**
     * @param consecutiveCount one for the first consecutive outcome
     * @param jitterUnit injected value in the inclusive range [0, 1]
     */
    public long delayMillis(Outcome outcome, int consecutiveCount, double jitterUnit) {
        if (consecutiveCount < 1) {
            throw new IllegalArgumentException("consecutiveCount must be positive");
        }
        if (!Double.isFinite(jitterUnit) || jitterUnit < 0.0d || jitterUnit > 1.0d) {
            throw new IllegalArgumentException("jitterUnit must be between 0 and 1");
        }

        long base = switch (outcome) {
            case ACTIVITY -> activityDelayMillis;
            case EMPTY -> exponentialDelay(emptyInitialDelayMillis, emptyMaxDelayMillis, consecutiveCount);
            case FAILURE -> exponentialDelay(failureInitialDelayMillis, failureMaxDelayMillis, consecutiveCount);
        };
        if (base == 0L || jitterRatio == 0.0d) {
            return base;
        }
        double factor = (1.0d - jitterRatio) + (2.0d * jitterRatio * jitterUnit);
        return Math.max(0L, Math.round(base * factor));
    }

    private long exponentialDelay(long initial, long maximum, int consecutiveCount) {
        if (initial == 0L || initial == maximum) {
            return initial;
        }
        double candidate = initial * Math.pow(multiplier, consecutiveCount - 1.0d);
        if (!Double.isFinite(candidate) || candidate >= maximum) {
            return maximum;
        }
        return Math.min(maximum, Math.round(candidate));
    }
}
