package org.kseries.rpg.season;

import java.util.Objects;
import java.util.UUID;

public record RewardClaim(
        String claimKey,
        String seasonId,
        UUID playerId,
        String rewardKey,
        RewardClaimState state,
        String payloadHash,
        int attempts,
        String lastError,
        long createdAt,
        long updatedAt,
        long grantedAt,
        long version
) {
    public RewardClaim {
        claimKey = SeasonIds.requireSourceKey(claimKey, "claim key");
        seasonId = SeasonIds.require(seasonId, "season id");
        playerId = Objects.requireNonNull(playerId, "playerId");
        rewardKey = SeasonIds.require(rewardKey, "reward key");
        state = Objects.requireNonNull(state, "state");
        payloadHash = Objects.requireNonNullElse(payloadHash, "").trim();
        lastError = Objects.requireNonNullElse(lastError, "");
        if (attempts < 0 || createdAt < 0 || updatedAt < createdAt || grantedAt < 0 || version < 0) {
            throw new IllegalArgumentException("reward claim values are invalid");
        }
        if (state == RewardClaimState.GRANTED && grantedAt == 0) {
            throw new IllegalArgumentException("granted claim must have grantedAt");
        }
    }
}
