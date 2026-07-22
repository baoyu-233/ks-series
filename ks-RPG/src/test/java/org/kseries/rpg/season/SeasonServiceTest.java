package org.kseries.rpg.season;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeasonServiceTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final long SEASON_START = 1;
    private static final long SECONDS_PER_WEEK = 7L * 24L * 60L * 60L;
    private static final long SEASON_END = SEASON_START + 100L * SECONDS_PER_WEEK;

    @Test
    void disabledByDefaultFailsClosed() {
        SeasonService service = new SeasonService();

        assertFalse(service.enabled());
        assertTrue(service.season("frostline").isEmpty());
        assertThrows(IllegalStateException.class,
                () -> service.registerSeason("frostline", "Frostline", 1, 10_000, "v1"));
    }

    @Test
    void catchupCreditIsCappedAndConsumedOnlyByEarnedReputation() {
        MutableClock clock = new MutableClock(1_000);
        SeasonService service = activeService(new SeasonRules(1_000, 3_000, 600), clock);

        SeasonService.ReputationGrant opening = service.grantReputation(
                "frostline", PLAYER, "north", 0, 200, 10);
        assertEquals(200, opening.progress().reputation());
        assertEquals(0, opening.progress().catchupCredit());

        clock.setEpochSecond(SEASON_START + 4L * SECONDS_PER_WEEK + 100);
        SeasonService.ReputationGrant late = service.grantReputation(
                "frostline", PLAYER, "north", 4, 100, 5);
        assertEquals(100, late.baseGranted());
        assertEquals(100, late.catchupGranted());
        assertEquals(2_900, late.progress().catchupCredit());
        assertTrue(late.progress().catchupCredit() <= 3_000);

        SeasonService.ReputationGrant capped = service.grantReputation(
                "frostline", PLAYER, "north", 4, 1_000, 20);
        assertEquals(900, capped.baseGranted());
        assertEquals(900, capped.catchupGranted());
        assertEquals(1_000, capped.progress().weeklyEarned());
        assertEquals(2_000, capped.progress().catchupCredit());

        SeasonService.ReputationGrant noMoreBase = service.grantReputation(
                "frostline", PLAYER, "north", 4, 100, 1);
        assertEquals(0, noMoreBase.baseGranted());
        assertEquals(0, noMoreBase.catchupGranted());
        assertEquals(2_000, noMoreBase.progress().catchupCredit());
    }

    @Test
    void lateJoinCreditAlsoRespectsCatchupCap() {
        MutableClock clock = new MutableClock(SEASON_START + 20L * SECONDS_PER_WEEK + 100);
        SeasonService service = activeService(new SeasonRules(1_000, 3_000, 600), clock);

        SeasonService.ReputationGrant grant = service.grantReputation(
                "frostline", PLAYER, "north", 20, 100, 1);

        assertEquals(100, grant.catchupGranted());
        assertEquals(2_900, grant.progress().catchupCredit());
    }

    @Test
    void activationAndActiveMutationsRespectSeasonTimeWindow() {
        MutableClock clock = new MutableClock(50);
        SeasonService service = SeasonService.enabled(new InMemorySeasonStore(), SeasonRules.defaults(), clock);
        service.initializeStore();
        assertTrue(service.registerSeason("short", "Short Season", 100, 200, "v1"));

        assertFalse(service.activateSeason("short"));
        clock.setEpochSecond(100);
        assertTrue(service.activateSeason("short"));
        assertEquals(10, service.grantReputation("short", PLAYER, "north", 0, 10, 1)
                .progress().reputation());

        clock.setEpochSecond(200);
        assertThrows(IllegalStateException.class,
                () -> service.grantReputation("short", PLAYER, "north", 0, 10, 1));
        assertThrows(IllegalStateException.class,
                () -> service.createProject("short", "late-project", 10));
    }

    @Test
    void callerCannotAdvanceWeekAheadOfServerClock() {
        MutableClock clock = new MutableClock(1_000);
        SeasonService service = activeService(new SeasonRules(1_000, 3_000, 600), clock);

        assertEquals(1_000, service.grantReputation(
                "frostline", PLAYER, "north", 0, 1_000, 10).progress().reputation());
        assertThrows(IllegalArgumentException.class, () -> service.grantReputation(
                "frostline", PLAYER, "north", 1, 1_000, 10));
        assertEquals(1_000, service.regionReputation("frostline", PLAYER, "north")
                .orElseThrow().reputation());

        clock.setEpochSecond(SEASON_START + SECONDS_PER_WEEK + 100);
        assertEquals(2_000, service.grantReputation(
                "frostline", PLAYER, "north", 1, 1_000, 10).progress().reputation());
    }

    @Test
    void inMemoryStoreRejectsProgressMutationAfterArchive() {
        InMemorySeasonStore store = new InMemorySeasonStore();
        store.insertSeason(new SeasonRecord("frostline", "Frostline", SeasonState.ACTIVE,
                1, 10_000, "v1", 10, 10));
        assertTrue(store.archiveSeason("frostline", 20).applied());

        RegionReputation progress = RegionReputation.initial("frostline", PLAYER, "north", 0, 0, 20);
        assertThrows(IllegalStateException.class, () -> store.insertRegionReputation(progress));
        assertThrows(IllegalStateException.class, () -> store.insertProject(
                new ProjectProgress("frostline", "late-project", 10, 0,
                        ProjectState.ACTIVE, 0, 20, 20, 0)));
    }

    @Test
    void projectSourceKeyAdvancesExactlyOnce() {
        SeasonService service = activeService(SeasonRules.defaults());
        assertTrue(service.createProject("frostline", "heat_grid", 100));

        SeasonStore.ProjectAdvance first = service.advanceProject(
                "frostline", "heat_grid", "event:run-1", 40);
        SeasonStore.ProjectAdvance duplicate = service.advanceProject(
                "frostline", "heat_grid", "EVENT:RUN-1", 40);
        SeasonStore.ProjectAdvance completed = service.advanceProject(
                "frostline", "heat_grid", "event:run-2", 70);
        SeasonStore.ProjectAdvance afterCompletion = service.advanceProject(
                "frostline", "heat_grid", "event:run-3", 1);

        assertTrue(first.applied());
        assertEquals(40, first.project().currentValue());
        assertFalse(duplicate.applied());
        assertEquals(40, duplicate.project().currentValue());
        assertTrue(completed.applied());
        assertEquals(100, completed.project().currentValue());
        assertEquals(ProjectState.COMPLETED, completed.project().state());
        assertFalse(afterCompletion.applied());
        assertEquals(100, afterCompletion.project().currentValue());
    }

    @Test
    void eventSnapshotsAreMonotonicAndMergeAbsolutePlayerScores() {
        SeasonService service = activeService(SeasonRules.defaults());
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");

        SeasonStore.SnapshotApply first = service.applyEventSnapshot(snapshot(1, Map.of(PLAYER, 10)));
        SeasonStore.SnapshotApply stale = service.applyEventSnapshot(snapshot(1, Map.of(PLAYER, 99)));
        SeasonStore.SnapshotApply next = service.applyEventSnapshot(snapshot(2, Map.of(PLAYER, 8, second, 5)));

        assertTrue(first.applied());
        assertFalse(stale.applied());
        assertTrue(next.applied());
        assertEquals(10, next.state().playerScores().get(PLAYER));
        assertEquals(5, next.state().playerScores().get(second));
        assertEquals(2, next.state().lastSequence());
    }

    @Test
    void rewardClaimTransitionsAreExplicitAndTerminalStatesCannotReopen() {
        SeasonService service = activeService(SeasonRules.defaults());
        assertTrue(service.createRewardClaim("season:frostline:player:1", "frostline", PLAYER,
                "veteran_badge", "sha256:test"));

        assertTrue(service.transitionRewardClaim("season:frostline:player:1",
                RewardClaimState.PENDING, RewardClaimState.DELIVERING, ""));
        assertTrue(service.transitionRewardClaim("season:frostline:player:1",
                RewardClaimState.DELIVERING, RewardClaimState.GRANTED, ""));
        assertThrows(IllegalArgumentException.class, () -> service.transitionRewardClaim(
                "season:frostline:player:1", RewardClaimState.GRANTED, RewardClaimState.DELIVERING, ""));

        RewardClaim claim = service.rewardClaim("season:frostline:player:1").orElseThrow();
        assertEquals(RewardClaimState.GRANTED, claim.state());
        assertEquals(1, claim.attempts());
        assertTrue(claim.grantedAt() > 0);
    }

    @Test
    void archiveAppendsHistoryWithoutDeletingProgressClaimsOrPermanentAssets() {
        SeasonService service = activeService(SeasonRules.defaults());
        service.grantReputation("frostline", PLAYER, "north", 0, 400, 30);
        service.grantReputation("frostline", PLAYER, "foundry", 0, 300, 20);
        service.createProject("frostline", "heat_grid", 100);
        service.advanceProject("frostline", "heat_grid", "event:run-1", 40);
        service.createRewardClaim("season:frostline:player:1", "frostline", PLAYER,
                "veteran_badge", "sha256:test");
        Map<UUID, Set<String>> permanentProofs = Map.of(
                PLAYER, Set.of("frostbound_conductor_clear"));

        assertTrue(service.beginSettlement("frostline"));
        SeasonStore.ArchiveResult archived = service.archiveSeason("frostline");
        SeasonStore.ArchiveResult repeated = service.archiveSeason("frostline");

        assertTrue(archived.applied());
        assertFalse(repeated.applied());
        assertEquals(SeasonState.ARCHIVED, service.season("frostline").orElseThrow().state());
        assertEquals(400, service.regionReputation("frostline", PLAYER, "north")
                .orElseThrow().reputation());
        assertEquals(40, service.project("frostline", "heat_grid").orElseThrow().currentValue());
        assertEquals(RewardClaimState.PENDING,
                service.rewardClaim("season:frostline:player:1").orElseThrow().state());
        assertEquals(Set.of("frostbound_conductor_clear"), permanentProofs.get(PLAYER));
        assertEquals(1, archived.entries().size());
        assertEquals(700, archived.entries().getFirst().totalReputation());
        assertEquals(50, archived.entries().getFirst().totalScore());
        assertEquals(2, archived.entries().getFirst().regionCount());
    }

    private SeasonService activeService(SeasonRules rules) {
        return activeService(rules, new MutableClock(1_000));
    }

    private SeasonService activeService(SeasonRules rules, Clock clock) {
        SeasonService service = SeasonService.enabled(new InMemorySeasonStore(), rules, clock);
        service.initializeStore();
        assertTrue(service.registerSeason("frostline", "Frostline Rekindled",
                SEASON_START, SEASON_END, "v1"));
        assertTrue(service.activateSeason("frostline"));
        return service;
    }

    private EventContributionSnapshot snapshot(long sequence, Map<UUID, Integer> scores) {
        return new EventContributionSnapshot("run-1", "frostline", "cold-surge", "north",
                "relay-a", sequence, 1_000 + sequence, scores);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(long epochSecond) {
            instant = Instant.ofEpochSecond(epochSecond);
        }

        private void setEpochSecond(long epochSecond) {
            instant = Instant.ofEpochSecond(epochSecond);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("only UTC is supported");
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
