package org.kseries.rpg.season;

import java.util.Objects;

public record ProjectProgress(
        String seasonId,
        String projectId,
        long targetValue,
        long currentValue,
        ProjectState state,
        long version,
        long createdAt,
        long updatedAt,
        long completedAt
) {
    public ProjectProgress {
        seasonId = SeasonIds.require(seasonId, "season id");
        projectId = SeasonIds.require(projectId, "project id");
        if (targetValue <= 0 || currentValue < 0 || currentValue > targetValue
                || version < 0 || createdAt < 0 || updatedAt < createdAt || completedAt < 0) {
            throw new IllegalArgumentException("project values are invalid");
        }
        state = Objects.requireNonNull(state, "state");
        if (state == ProjectState.COMPLETED && completedAt == 0) {
            throw new IllegalArgumentException("completed project must have completedAt");
        }
    }
}
