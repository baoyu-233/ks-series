package org.kseries.rpg.season;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure-Java season domain service. It performs no Bukkit access and owns no executor;
 * callers must invoke enabled storage operations from their database worker.
 */
public final class SeasonService {
    private static final int MAX_CAS_ATTEMPTS = 16;
    private static final long SECONDS_PER_WEEK = 7L * 24L * 60L * 60L;

    private final boolean enabled;
    private final SeasonStore store;
    private final SeasonRules rules;
    private final Clock clock;

    /** Creates a fail-closed service. No storage mutation is allowed until explicitly enabled. */
    public SeasonService() {
        this(false, new InMemorySeasonStore(), SeasonRules.defaults(), Clock.systemUTC());
    }

    private SeasonService(boolean enabled, SeasonStore store, SeasonRules rules, Clock clock) {
        this.enabled = enabled;
        this.store = Objects.requireNonNull(store, "store");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static SeasonService enabled(SeasonStore store, SeasonRules rules, Clock clock) {
        return new SeasonService(true, store, rules, clock);
    }

    public boolean enabled() {
        return enabled;
    }

    public void initializeStore() {
        requireEnabled();
        store.initialize();
    }

    public boolean registerSeason(String id, String displayName, long startsAt, long endsAt, String configHash) {
        requireEnabled();
        long now = now();
        return store.insertSeason(new SeasonRecord(id, displayName, SeasonState.DRAFT,
                startsAt, endsAt, configHash, now, now));
    }

    public boolean activateSeason(String seasonId) {
        requireEnabled();
        String normalizedSeason = SeasonIds.require(seasonId, "season id");
        SeasonRecord season = store.findSeason(normalizedSeason).orElse(null);
        if (season == null) return false;
        long now = now();
        if (now < season.startsAt() || now >= season.endsAt()) return false;
        return store.compareAndSetSeasonState(normalizedSeason,
                SeasonState.DRAFT, SeasonState.ACTIVE, now);
    }

    public boolean beginSettlement(String seasonId) {
        requireEnabled();
        return store.compareAndSetSeasonState(SeasonIds.require(seasonId, "season id"),
                SeasonState.ACTIVE, SeasonState.SETTLING, now());
    }

    public Optional<SeasonRecord> season(String seasonId) {
        if (!enabled) return Optional.empty();
        return store.findSeason(SeasonIds.require(seasonId, "season id"));
    }

    public Optional<RegionReputation> regionReputation(String seasonId, UUID playerId, String regionId) {
        if (!enabled) return Optional.empty();
        return store.findRegionReputation(SeasonIds.require(seasonId, "season id"),
                Objects.requireNonNull(playerId, "playerId"), SeasonIds.require(regionId, "region id"));
    }

    public ReputationGrant grantReputation(String seasonId, UUID playerId, String regionId,
                                           int weekIndex, int requestedBase, long contributionScore) {
        long now = now();
        SeasonRecord season = requireActiveSeason(seasonId, now);
        Objects.requireNonNull(playerId, "playerId");
        String normalizedSeason = season.id();
        String normalizedRegion = SeasonIds.require(regionId, "region id");
        if (weekIndex < 0 || requestedBase < 0 || contributionScore < 0) {
            throw new IllegalArgumentException("week, reputation and score must not be negative");
        }
        int serverWeekIndex = currentWeekIndex(season, now);
        if (weekIndex != serverWeekIndex) {
            throw new IllegalArgumentException("weekIndex does not match the current season week");
        }

        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            Optional<RegionReputation> stored = store.findRegionReputation(
                    normalizedSeason, playerId, normalizedRegion);
            RegionReputation current;
            boolean insert;
            if (stored.isPresent()) {
                current = stored.get();
                insert = false;
                if (weekIndex < current.weekIndex()) {
                    throw new IllegalArgumentException("weekIndex is older than stored progress");
                }
            } else {
                int lateJoinCredit = saturatedMultiply(weekIndex, rules.lateJoinCatchupPerWeek(), rules.catchupCap());
                current = RegionReputation.initial(normalizedSeason, playerId, normalizedRegion,
                        weekIndex, lateJoinCredit, now);
                insert = true;
            }

            RolledWeek rolled = rollWeek(current, weekIndex);
            int baseGranted = Math.min(requestedBase,
                    Math.max(0, rules.weeklyReputationCap() - rolled.weeklyEarned()));
            int catchupGranted = Math.min(baseGranted, rolled.catchupCredit());
            RegionReputation updated = new RegionReputation(normalizedSeason, playerId, normalizedRegion,
                    current.reputation() + baseGranted + catchupGranted,
                    weekIndex,
                    rolled.weeklyEarned() + baseGranted,
                    rolled.catchupCredit() - catchupGranted,
                    current.totalScore() + contributionScore,
                    insert ? 0 : current.version() + 1,
                    now);

            boolean applied = insert
                    ? store.insertRegionReputation(updated)
                    : store.compareAndSetRegionReputation(current.version(), updated);
            if (applied) return new ReputationGrant(baseGranted, catchupGranted, updated);
        }
        throw new SeasonStoreException("region reputation update exceeded retry limit", null);
    }

    public SeasonStore.SnapshotApply applyEventSnapshot(EventContributionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        requireActiveSeason(snapshot.seasonId(), now());
        return store.applyEventSnapshot(snapshot);
    }

    public Optional<EventContributionState> eventState(String runId) {
        if (!enabled) return Optional.empty();
        return store.findEventState(SeasonIds.require(runId, "event run id"));
    }

    public boolean createProject(String seasonId, String projectId, long targetValue) {
        long now = now();
        SeasonRecord season = requireActiveSeason(seasonId, now);
        if (targetValue <= 0) throw new IllegalArgumentException("targetValue must be positive");
        return store.insertProject(new ProjectProgress(season.id(), projectId, targetValue, 0,
                ProjectState.ACTIVE, 0, now, now, 0));
    }

    public Optional<ProjectProgress> project(String seasonId, String projectId) {
        if (!enabled) return Optional.empty();
        return store.findProject(SeasonIds.require(seasonId, "season id"),
                SeasonIds.require(projectId, "project id"));
    }

    public SeasonStore.ProjectAdvance advanceProject(String seasonId, String projectId,
                                                      String sourceKey, long delta) {
        long now = now();
        SeasonRecord season = requireActiveSeason(seasonId, now);
        if (delta <= 0) throw new IllegalArgumentException("delta must be positive");
        return store.advanceProject(season.id(),
                SeasonIds.require(projectId, "project id"),
                SeasonIds.requireSourceKey(sourceKey, "project source key"), delta, now);
    }

    public boolean createRewardClaim(String claimKey, String seasonId, UUID playerId,
                                     String rewardKey, String payloadHash) {
        long now = now();
        requireRewardSeason(seasonId, now);
        return store.insertRewardClaim(new RewardClaim(claimKey, seasonId, playerId, rewardKey,
                RewardClaimState.PENDING, payloadHash, 0, "", now, now, 0, 0));
    }

    public Optional<RewardClaim> rewardClaim(String claimKey) {
        if (!enabled) return Optional.empty();
        return store.findRewardClaim(SeasonIds.requireSourceKey(claimKey, "claim key"));
    }

    public boolean transitionRewardClaim(String claimKey, RewardClaimState expected,
                                         RewardClaimState next, String error) {
        requireEnabled();
        if (!allowedTransition(expected, next)) {
            throw new IllegalArgumentException("invalid reward claim transition: " + expected + " -> " + next);
        }
        Optional<RewardClaim> stored = store.findRewardClaim(SeasonIds.requireSourceKey(claimKey, "claim key"));
        if (stored.isEmpty() || stored.get().state() != expected) return false;
        RewardClaim current = stored.get();
        long now = now();
        RewardClaim updated = new RewardClaim(current.claimKey(), current.seasonId(), current.playerId(),
                current.rewardKey(), next, current.payloadHash(),
                current.attempts() + (next == RewardClaimState.DELIVERING ? 1 : 0),
                error, current.createdAt(), now,
                next == RewardClaimState.GRANTED ? now : current.grantedAt(), current.version() + 1);
        return store.compareAndSetRewardClaim(current.version(), updated);
    }

    public SeasonStore.ArchiveResult archiveSeason(String seasonId) {
        requireEnabled();
        SeasonRecord season = store.findSeason(SeasonIds.require(seasonId, "season id"))
                .orElseThrow(() -> new IllegalArgumentException("unknown season: " + seasonId));
        if (season.state() != SeasonState.ACTIVE && season.state() != SeasonState.SETTLING
                && season.state() != SeasonState.ARCHIVED) {
            throw new IllegalStateException("season cannot be archived from state " + season.state());
        }
        return store.archiveSeason(season.id(), now());
    }

    public java.util.List<SeasonArchiveEntry> archive(String seasonId) {
        if (!enabled) return java.util.List.of();
        return store.listArchive(SeasonIds.require(seasonId, "season id"));
    }

    private RolledWeek rollWeek(RegionReputation current, int requestedWeek) {
        if (requestedWeek == current.weekIndex()) {
            return new RolledWeek(current.weeklyEarned(), current.catchupCredit());
        }
        int elapsed = requestedWeek - current.weekIndex();
        long missed = Math.max(0, rules.weeklyReputationCap() - current.weeklyEarned());
        missed += (long) Math.max(0, elapsed - 1) * rules.weeklyReputationCap();
        int credit = (int) Math.min(rules.catchupCap(), current.catchupCredit() + missed);
        return new RolledWeek(0, credit);
    }

    private int saturatedMultiply(int left, int right, int cap) {
        return (int) Math.min(cap, (long) left * right);
    }

    private SeasonRecord requireActiveSeason(String seasonId, long at) {
        requireEnabled();
        SeasonRecord season = store.findSeason(SeasonIds.require(seasonId, "season id"))
                .orElseThrow(() -> new IllegalArgumentException("unknown season: " + seasonId));
        if (season.state() != SeasonState.ACTIVE) {
            throw new IllegalStateException("season is not active: " + season.id());
        }
        if (at < season.startsAt() || at >= season.endsAt()) {
            throw new IllegalStateException("season is outside its active time window: " + season.id());
        }
        return season;
    }

    private void requireRewardSeason(String seasonId, long at) {
        requireEnabled();
        SeasonRecord season = store.findSeason(SeasonIds.require(seasonId, "season id"))
                .orElseThrow(() -> new IllegalArgumentException("unknown season: " + seasonId));
        if (season.state() != SeasonState.ACTIVE && season.state() != SeasonState.SETTLING) {
            throw new IllegalStateException("season is not accepting rewards: " + season.id());
        }
        if (season.state() == SeasonState.ACTIVE
                && (at < season.startsAt() || at >= season.endsAt())) {
            throw new IllegalStateException("season is outside its active time window: " + season.id());
        }
    }

    private int currentWeekIndex(SeasonRecord season, long at) {
        long weekIndex = (at - season.startsAt()) / SECONDS_PER_WEEK;
        if (weekIndex > Integer.MAX_VALUE) {
            throw new IllegalStateException("season week index exceeds supported range: " + season.id());
        }
        return (int) weekIndex;
    }

    private void requireEnabled() {
        if (!enabled) throw new IllegalStateException("season service is disabled");
    }

    private long now() {
        return clock.instant().getEpochSecond();
    }

    private static boolean allowedTransition(RewardClaimState expected, RewardClaimState next) {
        return switch (expected) {
            case PENDING -> next == RewardClaimState.DELIVERING || next == RewardClaimState.RETRYABLE;
            case DELIVERING -> next == RewardClaimState.GRANTED || next == RewardClaimState.RETRYABLE
                    || next == RewardClaimState.COMPENSATION_REQUIRED;
            case RETRYABLE -> next == RewardClaimState.DELIVERING
                    || next == RewardClaimState.COMPENSATION_REQUIRED;
            case GRANTED, COMPENSATION_REQUIRED -> false;
        };
    }

    public record ReputationGrant(int baseGranted, int catchupGranted, RegionReputation progress) { }

    private record RolledWeek(int weeklyEarned, int catchupCredit) { }
}
