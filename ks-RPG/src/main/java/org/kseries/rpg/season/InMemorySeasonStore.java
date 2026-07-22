package org.kseries.rpg.season;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Deterministic store used by direct unit tests and by the disabled default service. */
final class InMemorySeasonStore implements SeasonStore {
    private final Map<String, SeasonRecord> seasons = new HashMap<>();
    private final Map<RegionKey, RegionReputation> reputations = new HashMap<>();
    private final Map<String, EventContributionState> events = new HashMap<>();
    private final Map<ProjectKey, ProjectProgress> projects = new HashMap<>();
    private final Map<ProjectKey, Set<String>> projectSources = new HashMap<>();
    private final Map<String, RewardClaim> rewardClaims = new HashMap<>();
    private final Map<ArchiveKey, SeasonArchiveEntry> archive = new HashMap<>();

    @Override
    public void initialize() { }

    @Override
    public synchronized boolean insertSeason(SeasonRecord season) {
        return seasons.putIfAbsent(season.id(), season) == null;
    }

    @Override
    public synchronized Optional<SeasonRecord> findSeason(String seasonId) {
        return Optional.ofNullable(seasons.get(seasonId));
    }

    @Override
    public synchronized boolean compareAndSetSeasonState(String seasonId, SeasonState expected,
                                                         SeasonState next, long updatedAt) {
        SeasonRecord current = seasons.get(seasonId);
        if (current == null || current.state() != expected) return false;
        seasons.put(seasonId, current.withState(next, updatedAt));
        return true;
    }

    @Override
    public synchronized Optional<RegionReputation> findRegionReputation(String seasonId, UUID playerId,
                                                                        String regionId) {
        return Optional.ofNullable(reputations.get(new RegionKey(seasonId, playerId, regionId)));
    }

    @Override
    public synchronized boolean insertRegionReputation(RegionReputation reputation) {
        requireSeasonState(reputation.seasonId(), SeasonState.ACTIVE);
        RegionKey key = RegionKey.of(reputation);
        return reputations.putIfAbsent(key, reputation) == null;
    }

    @Override
    public synchronized boolean compareAndSetRegionReputation(long expectedVersion,
                                                               RegionReputation updated) {
        requireSeasonState(updated.seasonId(), SeasonState.ACTIVE);
        RegionKey key = RegionKey.of(updated);
        RegionReputation current = reputations.get(key);
        if (current == null || current.version() != expectedVersion) return false;
        reputations.put(key, updated);
        return true;
    }

    @Override
    public synchronized SnapshotApply applyEventSnapshot(EventContributionSnapshot snapshot) {
        requireSeasonState(snapshot.seasonId(), SeasonState.ACTIVE);
        EventContributionState current = events.get(snapshot.runId());
        if (current != null) {
            if (!current.seasonId().equals(snapshot.seasonId())
                    || !current.eventId().equals(snapshot.eventId())
                    || !current.regionId().equals(snapshot.regionId())
                    || !current.arenaId().equals(snapshot.arenaId())) {
                throw new IllegalArgumentException("event run identity cannot change");
            }
            if (snapshot.sequence() <= current.lastSequence()) return new SnapshotApply(false, current);
        }

        Map<UUID, Integer> scores = new LinkedHashMap<>();
        if (current != null) scores.putAll(current.playerScores());
        snapshot.playerScores().forEach((playerId, score) -> scores.merge(playerId, score, Math::max));
        EventContributionState updated = new EventContributionState(snapshot.runId(), snapshot.seasonId(),
                snapshot.eventId(), snapshot.regionId(), snapshot.arenaId(), snapshot.sequence(),
                snapshot.capturedAt(), scores);
        events.put(snapshot.runId(), updated);
        return new SnapshotApply(true, updated);
    }

    @Override
    public synchronized Optional<EventContributionState> findEventState(String runId) {
        return Optional.ofNullable(events.get(runId));
    }

    @Override
    public synchronized boolean insertProject(ProjectProgress project) {
        requireSeasonState(project.seasonId(), SeasonState.ACTIVE);
        ProjectKey key = ProjectKey.of(project.seasonId(), project.projectId());
        return projects.putIfAbsent(key, project) == null;
    }

    @Override
    public synchronized Optional<ProjectProgress> findProject(String seasonId, String projectId) {
        return Optional.ofNullable(projects.get(ProjectKey.of(seasonId, projectId)));
    }

    @Override
    public synchronized ProjectAdvance advanceProject(String seasonId, String projectId,
                                                       String sourceKey, long delta, long updatedAt) {
        requireSeasonState(seasonId, SeasonState.ACTIVE);
        ProjectKey key = ProjectKey.of(seasonId, projectId);
        ProjectProgress current = projects.get(key);
        if (current == null) throw new IllegalArgumentException("unknown project: " + projectId);
        if (current.state() == ProjectState.COMPLETED) return new ProjectAdvance(false, current);
        Set<String> sources = projectSources.computeIfAbsent(key, ignored -> new HashSet<>());
        if (!sources.add(sourceKey)) return new ProjectAdvance(false, current);

        long value = current.currentValue() + Math.min(delta, current.targetValue() - current.currentValue());
        ProjectState state = value >= current.targetValue() ? ProjectState.COMPLETED : ProjectState.ACTIVE;
        long completedAt = state == ProjectState.COMPLETED
                ? (current.completedAt() == 0 ? updatedAt : current.completedAt()) : 0;
        ProjectProgress updated = new ProjectProgress(seasonId, projectId, current.targetValue(), value,
                state, current.version() + 1, current.createdAt(), updatedAt, completedAt);
        projects.put(key, updated);
        return new ProjectAdvance(true, updated);
    }

    @Override
    public synchronized boolean insertRewardClaim(RewardClaim claim) {
        requireSeasonState(claim.seasonId(), SeasonState.ACTIVE, SeasonState.SETTLING);
        return rewardClaims.putIfAbsent(claim.claimKey(), claim) == null;
    }

    @Override
    public synchronized Optional<RewardClaim> findRewardClaim(String claimKey) {
        return Optional.ofNullable(rewardClaims.get(claimKey));
    }

    @Override
    public synchronized boolean compareAndSetRewardClaim(long expectedVersion, RewardClaim updated) {
        RewardClaim current = rewardClaims.get(updated.claimKey());
        if (current == null || current.version() != expectedVersion) return false;
        rewardClaims.put(updated.claimKey(), updated);
        return true;
    }

    @Override
    public synchronized ArchiveResult archiveSeason(String seasonId, long archivedAt) {
        SeasonRecord season = seasons.get(seasonId);
        if (season == null) throw new IllegalArgumentException("unknown season: " + seasonId);
        if (season.state() == SeasonState.ARCHIVED) {
            return new ArchiveResult(false, listArchive(seasonId));
        }
        if (season.state() != SeasonState.ACTIVE && season.state() != SeasonState.SETTLING) {
            throw new IllegalStateException("season cannot be archived from state " + season.state());
        }

        Map<UUID, ArchiveAccumulator> totals = new HashMap<>();
        reputations.values().stream()
                .filter(progress -> progress.seasonId().equals(seasonId))
                .forEach(progress -> totals.computeIfAbsent(progress.playerId(), ignored -> new ArchiveAccumulator())
                        .add(progress));
        totals.forEach((playerId, total) -> archive.put(new ArchiveKey(seasonId, playerId),
                new SeasonArchiveEntry(seasonId, playerId, total.reputation, total.score,
                        total.regions, archivedAt)));
        seasons.put(seasonId, season.withState(SeasonState.ARCHIVED, archivedAt));
        return new ArchiveResult(true, listArchive(seasonId));
    }

    private void requireSeasonState(String seasonId, SeasonState... allowedStates) {
        SeasonRecord season = seasons.get(seasonId);
        if (season == null) throw new IllegalArgumentException("unknown season: " + seasonId);
        for (SeasonState allowedState : allowedStates) {
            if (season.state() == allowedState) return;
        }
        throw new IllegalStateException("season does not allow this mutation: " + seasonId);
    }

    @Override
    public synchronized List<SeasonArchiveEntry> listArchive(String seasonId) {
        return archive.values().stream()
                .filter(entry -> entry.seasonId().equals(seasonId))
                .sorted(Comparator.comparing(entry -> entry.playerId().toString()))
                .toList();
    }

    private record RegionKey(String seasonId, UUID playerId, String regionId) {
        static RegionKey of(RegionReputation reputation) {
            return new RegionKey(reputation.seasonId(), reputation.playerId(), reputation.regionId());
        }
    }

    private record ProjectKey(String seasonId, String projectId) {
        static ProjectKey of(String seasonId, String projectId) {
            return new ProjectKey(seasonId, projectId);
        }
    }

    private record ArchiveKey(String seasonId, UUID playerId) { }

    private static final class ArchiveAccumulator {
        private long reputation;
        private long score;
        private int regions;

        private void add(RegionReputation progress) {
            reputation = Math.addExact(reputation, progress.reputation());
            score = Math.addExact(score, progress.totalScore());
            regions++;
        }
    }
}
