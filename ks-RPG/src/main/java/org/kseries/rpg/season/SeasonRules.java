package org.kseries.rpg.season;

public record SeasonRules(
        int weeklyReputationCap,
        int catchupCap,
        int lateJoinCatchupPerWeek
) {
    public SeasonRules {
        if (weeklyReputationCap <= 0) {
            throw new IllegalArgumentException("weeklyReputationCap must be positive");
        }
        if (catchupCap < 0) {
            throw new IllegalArgumentException("catchupCap must not be negative");
        }
        if (lateJoinCatchupPerWeek < 0) {
            throw new IllegalArgumentException("lateJoinCatchupPerWeek must not be negative");
        }
    }

    public static SeasonRules defaults() {
        return new SeasonRules(1_000, 3_000, 600);
    }
}
