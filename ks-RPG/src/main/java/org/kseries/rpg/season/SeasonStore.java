package org.kseries.rpg.season;

import java.util.List;
import java.util.Optional;

public interface SeasonStore {
    void initialize();

    boolean insertSeason(SeasonRecord season);

    Optional<SeasonRecord> findSeason(String seasonId);

    boolean compareAndSetSeasonState(String seasonId, SeasonState expected, SeasonState next, long updatedAt);

    Optional<RegionReputation> findRegionReputation(String seasonId, java.util.UUID playerId, String regionId);

    boolean insertRegionReputation(RegionReputation reputation);

    boolean compareAndSetRegionReputation(long expectedVersion, RegionReputation updated);

    SnapshotApply applyEventSnapshot(EventContributionSnapshot snapshot);

    Optional<EventContributionState> findEventState(String runId);

    boolean insertProject(ProjectProgress project);

    Optional<ProjectProgress> findProject(String seasonId, String projectId);

    ProjectAdvance advanceProject(String seasonId, String projectId, String sourceKey, long delta, long updatedAt);

    boolean insertRewardClaim(RewardClaim claim);

    Optional<RewardClaim> findRewardClaim(String claimKey);

    boolean compareAndSetRewardClaim(long expectedVersion, RewardClaim updated);

    ArchiveResult archiveSeason(String seasonId, long archivedAt);

    List<SeasonArchiveEntry> listArchive(String seasonId);

    record SnapshotApply(boolean applied, EventContributionState state) { }

    record ProjectAdvance(boolean applied, ProjectProgress project) { }

    record ArchiveResult(boolean applied, List<SeasonArchiveEntry> entries) {
        public ArchiveResult {
            entries = List.copyOf(entries);
        }
    }
}
