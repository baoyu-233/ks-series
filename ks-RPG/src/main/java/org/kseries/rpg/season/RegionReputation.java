package org.kseries.rpg.season;

import java.util.Objects;
import java.util.UUID;

public record RegionReputation(
        String seasonId,
        UUID playerId,
        String regionId,
        long reputation,
        int weekIndex,
        int weeklyEarned,
        int catchupCredit,
        long totalScore,
        long version,
        long updatedAt
) {
    public RegionReputation {
        seasonId = SeasonIds.require(seasonId, "season id");
        playerId = Objects.requireNonNull(playerId, "playerId");
        regionId = SeasonIds.require(regionId, "region id");
        if (reputation < 0 || weekIndex < 0 || weeklyEarned < 0 || catchupCredit < 0
                || totalScore < 0 || version < 0 || updatedAt < 0) {
            throw new IllegalArgumentException("region reputation values must not be negative");
        }
    }

    static RegionReputation initial(String seasonId, UUID playerId, String regionId,
                                    int weekIndex, int catchupCredit, long now) {
        return new RegionReputation(seasonId, playerId, regionId, 0, weekIndex,
                0, catchupCredit, 0, 0, now);
    }
}
