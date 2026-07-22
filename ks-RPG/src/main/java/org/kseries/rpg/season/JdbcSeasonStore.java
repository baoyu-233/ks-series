package org.kseries.rpg.season;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Blocking JDBC store using SQLite-compatible SQL. A fresh connection is acquired per operation,
 * so the supplier may safely route calls through a dedicated database worker.
 */
public final class JdbcSeasonStore implements SeasonStore {
    private static final int TRANSACTION_RETRIES = 4;

    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    private final ConnectionFactory connections;

    public JdbcSeasonStore(ConnectionFactory connections) {
        this.connections = java.util.Objects.requireNonNull(connections, "connections");
    }

    @Override
    public void initialize() {
        try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_rpg_seasons (
                        id VARCHAR(128) PRIMARY KEY,
                        display_name VARCHAR(255) NOT NULL,
                        state VARCHAR(32) NOT NULL,
                        starts_at BIGINT NOT NULL,
                        ends_at BIGINT NOT NULL,
                        config_hash VARCHAR(128) NOT NULL DEFAULT '',
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_rpg_region_reputation (
                        season_id VARCHAR(128) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        region_id VARCHAR(128) NOT NULL,
                        reputation BIGINT NOT NULL,
                        week_index INTEGER NOT NULL,
                        weekly_earned INTEGER NOT NULL,
                        catchup_credit INTEGER NOT NULL,
                        total_score BIGINT NOT NULL,
                        version BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (season_id, player_uuid, region_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_rpg_event_runs (
                        run_id VARCHAR(128) PRIMARY KEY,
                        season_id VARCHAR(128) NOT NULL,
                        event_id VARCHAR(128) NOT NULL,
                        region_id VARCHAR(128) NOT NULL,
                        arena_id VARCHAR(128) NOT NULL,
                        last_sequence BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_rpg_event_players (
                        run_id VARCHAR(128) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        score INTEGER NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (run_id, player_uuid)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_rpg_projects (
                        season_id VARCHAR(128) NOT NULL,
                        project_id VARCHAR(128) NOT NULL,
                        target_value BIGINT NOT NULL,
                        current_value BIGINT NOT NULL,
                        state VARCHAR(32) NOT NULL,
                        version BIGINT NOT NULL,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        completed_at BIGINT NOT NULL DEFAULT 0,
                        PRIMARY KEY (season_id, project_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_rpg_project_sources (
                        season_id VARCHAR(128) NOT NULL,
                        project_id VARCHAR(128) NOT NULL,
                        source_key VARCHAR(191) NOT NULL,
                        delta BIGINT NOT NULL,
                        created_at BIGINT NOT NULL,
                        PRIMARY KEY (season_id, project_id, source_key)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_rpg_reward_claims (
                        claim_key VARCHAR(191) PRIMARY KEY,
                        season_id VARCHAR(128) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        reward_key VARCHAR(128) NOT NULL,
                        state VARCHAR(32) NOT NULL,
                        payload_hash VARCHAR(128) NOT NULL DEFAULT '',
                        attempts INTEGER NOT NULL,
                        last_error TEXT NOT NULL DEFAULT '',
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        granted_at BIGINT NOT NULL DEFAULT 0,
                        version BIGINT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_rpg_season_archive (
                        season_id VARCHAR(128) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        total_reputation BIGINT NOT NULL,
                        total_score BIGINT NOT NULL,
                        region_count INTEGER NOT NULL,
                        archived_at BIGINT NOT NULL,
                        PRIMARY KEY (season_id, player_uuid)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_rpg_region_player ON ks_rpg_region_reputation(player_uuid, season_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_rpg_reward_state ON ks_rpg_reward_claims(state, updated_at)");
        } catch (SQLException failure) {
            throw storeFailure("initialize season schema", failure);
        }
    }

    @Override
    public boolean insertSeason(SeasonRecord season) {
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ks_rpg_seasons
                (id,display_name,state,starts_at,ends_at,config_hash,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """)) {
            writeSeason(statement, season);
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            if (isConstraintViolation(failure)) return false;
            throw storeFailure("insert season", failure);
        }
    }

    @Override
    public Optional<SeasonRecord> findSeason(String seasonId) {
        try (Connection connection = connections.open()) {
            return Optional.ofNullable(loadSeason(connection, seasonId));
        } catch (SQLException failure) {
            throw storeFailure("find season", failure);
        }
    }

    @Override
    public boolean compareAndSetSeasonState(String seasonId, SeasonState expected,
                                            SeasonState next, long updatedAt) {
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE ks_rpg_seasons SET state=?,updated_at=? WHERE id=? AND state=?")) {
            statement.setString(1, next.name());
            statement.setLong(2, updatedAt);
            statement.setString(3, seasonId);
            statement.setString(4, expected.name());
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw storeFailure("change season state", failure);
        }
    }

    @Override
    public Optional<RegionReputation> findRegionReputation(String seasonId, UUID playerId, String regionId) {
        try (Connection connection = connections.open()) {
            return Optional.ofNullable(loadRegionReputation(connection, seasonId, playerId, regionId));
        } catch (SQLException failure) {
            throw storeFailure("find region reputation", failure);
        }
    }

    @Override
    public boolean insertRegionReputation(RegionReputation reputation) {
        try {
            return transaction("insert region reputation", connection -> {
                requireSeasonState(connection, reputation.seasonId(), SeasonState.ACTIVE);
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ks_rpg_region_reputation
                        (season_id,player_uuid,region_id,reputation,week_index,weekly_earned,catchup_credit,total_score,version,updated_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?)
                        """)) {
                    writeRegionReputation(statement, reputation);
                    return statement.executeUpdate() == 1;
                }
            });
        } catch (SeasonStoreException failure) {
            if (isConstraintViolation(failure.getCause())) return false;
            throw failure;
        }
    }

    @Override
    public boolean compareAndSetRegionReputation(long expectedVersion, RegionReputation updated) {
        return transaction("update region reputation", connection -> {
            requireSeasonState(connection, updated.seasonId(), SeasonState.ACTIVE);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ks_rpg_region_reputation
                    SET reputation=?,week_index=?,weekly_earned=?,catchup_credit=?,total_score=?,version=?,updated_at=?
                    WHERE season_id=? AND player_uuid=? AND region_id=? AND version=?
                    """)) {
                statement.setLong(1, updated.reputation());
                statement.setInt(2, updated.weekIndex());
                statement.setInt(3, updated.weeklyEarned());
                statement.setInt(4, updated.catchupCredit());
                statement.setLong(5, updated.totalScore());
                statement.setLong(6, updated.version());
                statement.setLong(7, updated.updatedAt());
                statement.setString(8, updated.seasonId());
                statement.setString(9, updated.playerId().toString());
                statement.setString(10, updated.regionId());
                statement.setLong(11, expectedVersion);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public SnapshotApply applyEventSnapshot(EventContributionSnapshot snapshot) {
        for (int attempt = 0; attempt < TRANSACTION_RETRIES; attempt++) {
            try {
                return transaction("apply event contribution snapshot", connection -> {
                    requireSeasonState(connection, snapshot.seasonId(), SeasonState.ACTIVE);
                    EventContributionState current = loadEventState(connection, snapshot.runId());
                    if (current != null) {
                        requireSameEventIdentity(current, snapshot);
                        if (snapshot.sequence() <= current.lastSequence()) {
                            return new SnapshotApply(false, current);
                        }
                        try (PreparedStatement update = connection.prepareStatement("""
                                UPDATE ks_rpg_event_runs SET last_sequence=?,updated_at=?
                                WHERE run_id=? AND last_sequence=?
                                """)) {
                            update.setLong(1, snapshot.sequence());
                            update.setLong(2, snapshot.capturedAt());
                            update.setString(3, snapshot.runId());
                            update.setLong(4, current.lastSequence());
                            if (update.executeUpdate() != 1) throw optimisticConflict();
                        }
                    } else {
                        try (PreparedStatement insert = connection.prepareStatement("""
                                INSERT INTO ks_rpg_event_runs
                                (run_id,season_id,event_id,region_id,arena_id,last_sequence,updated_at)
                                VALUES (?,?,?,?,?,?,?)
                                """)) {
                            insert.setString(1, snapshot.runId());
                            insert.setString(2, snapshot.seasonId());
                            insert.setString(3, snapshot.eventId());
                            insert.setString(4, snapshot.regionId());
                            insert.setString(5, snapshot.arenaId());
                            insert.setLong(6, snapshot.sequence());
                            insert.setLong(7, snapshot.capturedAt());
                            insert.executeUpdate();
                        }
                    }
                    for (Map.Entry<UUID, Integer> entry : snapshot.playerScores().entrySet()) {
                        upsertEventScore(connection, snapshot.runId(), entry.getKey(), entry.getValue(),
                                snapshot.capturedAt());
                    }
                    return new SnapshotApply(true, loadEventState(connection, snapshot.runId()));
                });
            } catch (SeasonStoreException failure) {
                if (isRetryable(failure) && attempt + 1 < TRANSACTION_RETRIES) continue;
                throw failure;
            }
        }
        throw new SeasonStoreException("event snapshot update exceeded retry limit", null);
    }

    @Override
    public Optional<EventContributionState> findEventState(String runId) {
        try (Connection connection = connections.open()) {
            return Optional.ofNullable(loadEventState(connection, runId));
        } catch (SQLException failure) {
            throw storeFailure("find event state", failure);
        }
    }

    @Override
    public boolean insertProject(ProjectProgress project) {
        try {
            return transaction("insert project", connection -> {
                requireSeasonState(connection, project.seasonId(), SeasonState.ACTIVE);
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ks_rpg_projects
                        (season_id,project_id,target_value,current_value,state,version,created_at,updated_at,completed_at)
                        VALUES (?,?,?,?,?,?,?,?,?)
                        """)) {
                    writeProject(statement, project);
                    return statement.executeUpdate() == 1;
                }
            });
        } catch (SeasonStoreException failure) {
            if (isConstraintViolation(failure.getCause())) return false;
            throw failure;
        }
    }

    @Override
    public Optional<ProjectProgress> findProject(String seasonId, String projectId) {
        try (Connection connection = connections.open()) {
            return Optional.ofNullable(loadProject(connection, seasonId, projectId));
        } catch (SQLException failure) {
            throw storeFailure("find project", failure);
        }
    }

    @Override
    public ProjectAdvance advanceProject(String seasonId, String projectId,
                                          String sourceKey, long delta, long updatedAt) {
        for (int attempt = 0; attempt < TRANSACTION_RETRIES; attempt++) {
            try {
                return transaction("advance project", connection -> {
                    requireSeasonState(connection, seasonId, SeasonState.ACTIVE);
                    ProjectProgress current = loadProject(connection, seasonId, projectId);
                    if (current == null) throw new IllegalArgumentException("unknown project: " + projectId);
                    if (current.state() == ProjectState.COMPLETED) {
                        return new ProjectAdvance(false, current);
                    }
                    if (projectSourceExists(connection, seasonId, projectId, sourceKey)) {
                        return new ProjectAdvance(false, current);
                    }
                    try (PreparedStatement source = connection.prepareStatement("""
                            INSERT INTO ks_rpg_project_sources
                            (season_id,project_id,source_key,delta,created_at) VALUES (?,?,?,?,?)
                            """)) {
                        source.setString(1, seasonId);
                        source.setString(2, projectId);
                        source.setString(3, sourceKey);
                        source.setLong(4, delta);
                        source.setLong(5, updatedAt);
                        source.executeUpdate();
                    }

                    long remaining = current.targetValue() - current.currentValue();
                    long appliedDelta = Math.min(delta, remaining);
                    long value = current.currentValue() + appliedDelta;
                    ProjectState state = value >= current.targetValue() ? ProjectState.COMPLETED : ProjectState.ACTIVE;
                    long completedAt = state == ProjectState.COMPLETED
                            ? (current.completedAt() == 0 ? updatedAt : current.completedAt()) : 0;
                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE ks_rpg_projects
                            SET current_value=?,state=?,version=?,updated_at=?,completed_at=?
                            WHERE season_id=? AND project_id=? AND version=?
                            """)) {
                        update.setLong(1, value);
                        update.setString(2, state.name());
                        update.setLong(3, current.version() + 1);
                        update.setLong(4, updatedAt);
                        update.setLong(5, completedAt);
                        update.setString(6, seasonId);
                        update.setString(7, projectId);
                        update.setLong(8, current.version());
                        if (update.executeUpdate() != 1) throw optimisticConflict();
                    }
                    return new ProjectAdvance(true, loadProject(connection, seasonId, projectId));
                });
            } catch (SeasonStoreException failure) {
                if (isConstraintViolation(failure.getCause())) {
                    Optional<ProjectProgress> project = findProject(seasonId, projectId);
                    if (project.isPresent() && sourceExists(seasonId, projectId, sourceKey)) {
                        return new ProjectAdvance(false, project.get());
                    }
                }
                if (isRetryable(failure) && attempt + 1 < TRANSACTION_RETRIES) continue;
                throw failure;
            }
        }
        throw new SeasonStoreException("project update exceeded retry limit", null);
    }

    @Override
    public boolean insertRewardClaim(RewardClaim claim) {
        try {
            return transaction("insert reward claim", connection -> {
                requireSeasonState(connection, claim.seasonId(), SeasonState.ACTIVE, SeasonState.SETTLING);
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ks_rpg_reward_claims
                        (claim_key,season_id,player_uuid,reward_key,state,payload_hash,attempts,last_error,created_at,updated_at,granted_at,version)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                        """)) {
                    writeRewardClaim(statement, claim);
                    return statement.executeUpdate() == 1;
                }
            });
        } catch (SeasonStoreException failure) {
            if (isConstraintViolation(failure.getCause())) return false;
            throw failure;
        }
    }

    @Override
    public Optional<RewardClaim> findRewardClaim(String claimKey) {
        try (Connection connection = connections.open()) {
            return Optional.ofNullable(loadRewardClaim(connection, claimKey));
        } catch (SQLException failure) {
            throw storeFailure("find reward claim", failure);
        }
    }

    @Override
    public boolean compareAndSetRewardClaim(long expectedVersion, RewardClaim updated) {
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE ks_rpg_reward_claims
                SET state=?,attempts=?,last_error=?,updated_at=?,granted_at=?,version=?
                WHERE claim_key=? AND version=?
                """)) {
            statement.setString(1, updated.state().name());
            statement.setInt(2, updated.attempts());
            statement.setString(3, updated.lastError());
            statement.setLong(4, updated.updatedAt());
            statement.setLong(5, updated.grantedAt());
            statement.setLong(6, updated.version());
            statement.setString(7, updated.claimKey());
            statement.setLong(8, expectedVersion);
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw storeFailure("update reward claim", failure);
        }
    }

    @Override
    public ArchiveResult archiveSeason(String seasonId, long archivedAt) {
        try {
            return transaction("archive season", connection -> {
                SeasonRecord season = lockSeason(connection, seasonId);
                if (season == null) throw new IllegalArgumentException("unknown season: " + seasonId);
                if (season.state() == SeasonState.ARCHIVED) {
                    return new ArchiveResult(false, loadArchive(connection, seasonId));
                }
                if (season.state() != SeasonState.ACTIVE && season.state() != SeasonState.SETTLING) {
                    throw new IllegalStateException("season cannot be archived from state " + season.state());
                }

                try (PreparedStatement totals = connection.prepareStatement("""
                        SELECT player_uuid,COALESCE(SUM(reputation),0),COALESCE(SUM(total_score),0),COUNT(*)
                        FROM ks_rpg_region_reputation WHERE season_id=? GROUP BY player_uuid
                        """)) {
                    totals.setString(1, seasonId);
                    try (ResultSet rows = totals.executeQuery()) {
                        while (rows.next()) {
                            SeasonArchiveEntry entry = new SeasonArchiveEntry(seasonId,
                                    UUID.fromString(rows.getString(1)), rows.getLong(2), rows.getLong(3),
                                    rows.getInt(4), archivedAt);
                            upsertArchive(connection, entry);
                        }
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE ks_rpg_seasons SET state=?,updated_at=? WHERE id=? AND state=?")) {
                    update.setString(1, SeasonState.ARCHIVED.name());
                    update.setLong(2, archivedAt);
                    update.setString(3, seasonId);
                    update.setString(4, season.state().name());
                    if (update.executeUpdate() != 1) throw optimisticConflict();
                }
                return new ArchiveResult(true, loadArchive(connection, seasonId));
            });
        } catch (SeasonStoreException failure) {
            if (isConstraintViolation(failure.getCause())) {
                return new ArchiveResult(false, listArchive(seasonId));
            }
            throw failure;
        }
    }

    @Override
    public List<SeasonArchiveEntry> listArchive(String seasonId) {
        try (Connection connection = connections.open()) {
            return loadArchive(connection, seasonId);
        } catch (SQLException failure) {
            throw storeFailure("list season archive", failure);
        }
    }

    private SeasonRecord loadSeason(Connection connection, String seasonId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,display_name,state,starts_at,ends_at,config_hash,created_at,updated_at FROM ks_rpg_seasons WHERE id=?")) {
            statement.setString(1, seasonId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new SeasonRecord(rows.getString(1), rows.getString(2), SeasonState.valueOf(rows.getString(3)),
                        rows.getLong(4), rows.getLong(5), rows.getString(6), rows.getLong(7), rows.getLong(8));
            }
        }
    }

    private SeasonRecord lockSeason(Connection connection, String seasonId) throws SQLException {
        // Every progression mutation and archive takes this row lock first, giving them one order.
        try (PreparedStatement lock = connection.prepareStatement(
                "UPDATE ks_rpg_seasons SET state=state WHERE id=?")) {
            lock.setString(1, seasonId);
            lock.executeUpdate();
        }
        return loadSeason(connection, seasonId);
    }

    private SeasonRecord requireSeasonState(Connection connection, String seasonId,
                                            SeasonState... allowedStates) throws SQLException {
        SeasonRecord season = lockSeason(connection, seasonId);
        if (season == null) throw new IllegalArgumentException("unknown season: " + seasonId);
        for (SeasonState allowedState : allowedStates) {
            if (season.state() == allowedState) return season;
        }
        throw new IllegalStateException("season does not allow this mutation: " + seasonId);
    }

    private RegionReputation loadRegionReputation(Connection connection, String seasonId,
                                                   UUID playerId, String regionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT reputation,week_index,weekly_earned,catchup_credit,total_score,version,updated_at
                FROM ks_rpg_region_reputation WHERE season_id=? AND player_uuid=? AND region_id=?
                """)) {
            statement.setString(1, seasonId);
            statement.setString(2, playerId.toString());
            statement.setString(3, regionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new RegionReputation(seasonId, playerId, regionId, rows.getLong(1), rows.getInt(2),
                        rows.getInt(3), rows.getInt(4), rows.getLong(5), rows.getLong(6), rows.getLong(7));
            }
        }
    }

    private EventContributionState loadEventState(Connection connection, String runId) throws SQLException {
        String seasonId;
        String eventId;
        String regionId;
        String arenaId;
        long sequence;
        long updatedAt;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT season_id,event_id,region_id,arena_id,last_sequence,updated_at
                FROM ks_rpg_event_runs WHERE run_id=?
                """)) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                seasonId = rows.getString(1);
                eventId = rows.getString(2);
                regionId = rows.getString(3);
                arenaId = rows.getString(4);
                sequence = rows.getLong(5);
                updatedAt = rows.getLong(6);
            }
        }
        Map<UUID, Integer> scores = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid,score FROM ks_rpg_event_players WHERE run_id=? ORDER BY player_uuid")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) scores.put(UUID.fromString(rows.getString(1)), rows.getInt(2));
            }
        }
        return new EventContributionState(runId, seasonId, eventId, regionId, arenaId,
                sequence, updatedAt, scores);
    }

    private ProjectProgress loadProject(Connection connection, String seasonId, String projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT target_value,current_value,state,version,created_at,updated_at,completed_at
                FROM ks_rpg_projects WHERE season_id=? AND project_id=?
                """)) {
            statement.setString(1, seasonId);
            statement.setString(2, projectId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new ProjectProgress(seasonId, projectId, rows.getLong(1), rows.getLong(2),
                        ProjectState.valueOf(rows.getString(3)), rows.getLong(4), rows.getLong(5),
                        rows.getLong(6), rows.getLong(7));
            }
        }
    }

    private RewardClaim loadRewardClaim(Connection connection, String claimKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT season_id,player_uuid,reward_key,state,payload_hash,attempts,last_error,
                       created_at,updated_at,granted_at,version
                FROM ks_rpg_reward_claims WHERE claim_key=?
                """)) {
            statement.setString(1, claimKey);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new RewardClaim(claimKey, rows.getString(1), UUID.fromString(rows.getString(2)),
                        rows.getString(3), RewardClaimState.valueOf(rows.getString(4)), rows.getString(5),
                        rows.getInt(6), rows.getString(7), rows.getLong(8), rows.getLong(9),
                        rows.getLong(10), rows.getLong(11));
            }
        }
    }

    private List<SeasonArchiveEntry> loadArchive(Connection connection, String seasonId) throws SQLException {
        List<SeasonArchiveEntry> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_uuid,total_reputation,total_score,region_count,archived_at
                FROM ks_rpg_season_archive WHERE season_id=? ORDER BY player_uuid
                """)) {
            statement.setString(1, seasonId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) entries.add(new SeasonArchiveEntry(seasonId,
                        UUID.fromString(rows.getString(1)), rows.getLong(2), rows.getLong(3),
                        rows.getInt(4), rows.getLong(5)));
            }
        }
        return List.copyOf(entries);
    }

    private void upsertEventScore(Connection connection, String runId, UUID playerId,
                                  int score, long updatedAt) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE ks_rpg_event_players
                SET score=CASE WHEN score<? THEN ? ELSE score END,updated_at=?
                WHERE run_id=? AND player_uuid=?
                """)) {
            update.setInt(1, score);
            update.setInt(2, score);
            update.setLong(3, updatedAt);
            update.setString(4, runId);
            update.setString(5, playerId.toString());
            if (update.executeUpdate() == 1) return;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO ks_rpg_event_players(run_id,player_uuid,score,updated_at) VALUES (?,?,?,?)")) {
            insert.setString(1, runId);
            insert.setString(2, playerId.toString());
            insert.setInt(3, score);
            insert.setLong(4, updatedAt);
            insert.executeUpdate();
        }
    }

    private void upsertArchive(Connection connection, SeasonArchiveEntry entry) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE ks_rpg_season_archive
                SET total_reputation=?,total_score=?,region_count=?,archived_at=?
                WHERE season_id=? AND player_uuid=?
                """)) {
            update.setLong(1, entry.totalReputation());
            update.setLong(2, entry.totalScore());
            update.setInt(3, entry.regionCount());
            update.setLong(4, entry.archivedAt());
            update.setString(5, entry.seasonId());
            update.setString(6, entry.playerId().toString());
            if (update.executeUpdate() == 1) return;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO ks_rpg_season_archive
                (season_id,player_uuid,total_reputation,total_score,region_count,archived_at)
                VALUES (?,?,?,?,?,?)
                """)) {
            insert.setString(1, entry.seasonId());
            insert.setString(2, entry.playerId().toString());
            insert.setLong(3, entry.totalReputation());
            insert.setLong(4, entry.totalScore());
            insert.setInt(5, entry.regionCount());
            insert.setLong(6, entry.archivedAt());
            insert.executeUpdate();
        }
    }

    private boolean sourceExists(String seasonId, String projectId, String sourceKey) {
        try (Connection connection = connections.open()) {
            return projectSourceExists(connection, seasonId, projectId, sourceKey);
        } catch (SQLException failure) {
            throw storeFailure("check project source", failure);
        }
    }

    private boolean projectSourceExists(Connection connection, String seasonId,
                                        String projectId, String sourceKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM ks_rpg_project_sources
                WHERE season_id=? AND project_id=? AND source_key=?
                """)) {
            statement.setString(1, seasonId);
            statement.setString(2, projectId);
            statement.setString(3, sourceKey);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private void writeSeason(PreparedStatement statement, SeasonRecord season) throws SQLException {
        statement.setString(1, season.id());
        statement.setString(2, season.displayName());
        statement.setString(3, season.state().name());
        statement.setLong(4, season.startsAt());
        statement.setLong(5, season.endsAt());
        statement.setString(6, season.configHash());
        statement.setLong(7, season.createdAt());
        statement.setLong(8, season.updatedAt());
    }

    private void writeRegionReputation(PreparedStatement statement, RegionReputation progress) throws SQLException {
        statement.setString(1, progress.seasonId());
        statement.setString(2, progress.playerId().toString());
        statement.setString(3, progress.regionId());
        statement.setLong(4, progress.reputation());
        statement.setInt(5, progress.weekIndex());
        statement.setInt(6, progress.weeklyEarned());
        statement.setInt(7, progress.catchupCredit());
        statement.setLong(8, progress.totalScore());
        statement.setLong(9, progress.version());
        statement.setLong(10, progress.updatedAt());
    }

    private void writeProject(PreparedStatement statement, ProjectProgress project) throws SQLException {
        statement.setString(1, project.seasonId());
        statement.setString(2, project.projectId());
        statement.setLong(3, project.targetValue());
        statement.setLong(4, project.currentValue());
        statement.setString(5, project.state().name());
        statement.setLong(6, project.version());
        statement.setLong(7, project.createdAt());
        statement.setLong(8, project.updatedAt());
        statement.setLong(9, project.completedAt());
    }

    private void writeRewardClaim(PreparedStatement statement, RewardClaim claim) throws SQLException {
        statement.setString(1, claim.claimKey());
        statement.setString(2, claim.seasonId());
        statement.setString(3, claim.playerId().toString());
        statement.setString(4, claim.rewardKey());
        statement.setString(5, claim.state().name());
        statement.setString(6, claim.payloadHash());
        statement.setInt(7, claim.attempts());
        statement.setString(8, claim.lastError());
        statement.setLong(9, claim.createdAt());
        statement.setLong(10, claim.updatedAt());
        statement.setLong(11, claim.grantedAt());
        statement.setLong(12, claim.version());
    }

    private void requireSameEventIdentity(EventContributionState current,
                                          EventContributionSnapshot snapshot) {
        if (!current.seasonId().equals(snapshot.seasonId())
                || !current.eventId().equals(snapshot.eventId())
                || !current.regionId().equals(snapshot.regionId())
                || !current.arenaId().equals(snapshot.arenaId())) {
            throw new IllegalArgumentException("event run identity cannot change");
        }
    }

    private <T> T transaction(String operation, SqlWork<T> work) {
        try (Connection connection = connections.open()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                if (failure instanceof SeasonStoreException storeFailure) throw storeFailure;
                if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
                throw storeFailure(operation, (SQLException) failure);
            } finally {
                try {
                    connection.setAutoCommit(oldAutoCommit);
                } catch (SQLException ignored) { }
            }
        } catch (SQLException failure) {
            throw storeFailure(operation, failure);
        }
    }

    private SQLException optimisticConflict() {
        return new SQLException("optimistic write conflict", "40001");
    }

    private boolean isRetryable(SeasonStoreException failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof SQLException sql && "40001".equals(sql.getSQLState())) return true;
        return isConstraintViolation(cause);
    }

    private boolean isConstraintViolation(Throwable failure) {
        if (!(failure instanceof SQLException sql)) return false;
        String state = sql.getSQLState();
        if (state != null && state.startsWith("23")) return true;
        String message = sql.getMessage();
        if (message == null) return false;
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("unique") || normalized.contains("constraint")
                || normalized.contains("primary key");
    }

    private SeasonStoreException storeFailure(String operation, SQLException failure) {
        return new SeasonStoreException("Failed to " + operation, failure);
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
