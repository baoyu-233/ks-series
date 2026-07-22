package org.kseries.instanceworld.internal;

import org.kseries.instanceworld.api.GridSnapshot;
import org.kseries.instanceworld.api.InstanceBounds;
import org.kseries.instanceworld.api.InstanceGridSpec;
import org.kseries.instanceworld.api.InstancePoint;
import org.kseries.instanceworld.api.InstancePrepareRequest;
import org.kseries.instanceworld.api.InstanceSnapshot;
import org.kseries.instanceworld.api.InstanceState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public final class InstanceStore {
    private final Path database;
    private final Logger logger;

    public InstanceStore(Path database, Logger logger) {
        this.database = database;
        this.logger = logger;
    }

    public void initialize() throws Exception {
        Files.createDirectories(database.getParent());
        Class.forName(org.sqlite.JDBC.class.getName());
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS iw_pools (
                        world TEXT PRIMARY KEY,
                        spacing INTEGER NOT NULL,
                        max_grids INTEGER NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS iw_grids (
                        id TEXT PRIMARY KEY,
                        world TEXT NOT NULL,
                        grid_x INTEGER NOT NULL,
                        grid_z INTEGER NOT NULL,
                        status TEXT NOT NULL DEFAULT 'FREE',
                        occupied_by TEXT,
                        occupied_since INTEGER NOT NULL DEFAULT 0,
                        last_used_at INTEGER NOT NULL DEFAULT 0,
                        UNIQUE(world, grid_x, grid_z)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_iw_grids_status ON iw_grids(world, status, last_used_at)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS iw_instances (
                        id TEXT PRIMARY KEY,
                        namespace TEXT NOT NULL,
                        template_key TEXT NOT NULL,
                        schematic_name TEXT NOT NULL,
                        grid_id TEXT NOT NULL,
                        state TEXT NOT NULL,
                        arena_radius INTEGER NOT NULL,
                        min_x INTEGER,
                        min_y INTEGER,
                        min_z INTEGER,
                        max_x INTEGER,
                        max_y INTEGER,
                        max_z INTEGER,
                        center_x REAL,
                        center_y REAL,
                        center_z REAL,
                        spawn_x REAL,
                        spawn_y REAL,
                        spawn_z REAL,
                        owner_session TEXT NOT NULL,
                        error TEXT NOT NULL DEFAULT '',
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        FOREIGN KEY(grid_id) REFERENCES iw_grids(id)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_iw_instances_state ON iw_instances(state)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS iw_meta (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """);
        }
    }

    public synchronized int importLegacyGrids(Path sourceDatabase, String table) throws SQLException {
        if (sourceDatabase == null || !Files.isRegularFile(sourceDatabase)) return 0;
        if (table == null || !table.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Unsafe legacy table name");
        }
        String migrationKey = "legacy-grid-import:" + sourceDatabase.toAbsolutePath().normalize() + ":" + table;
        try (Connection target = open()) {
            if (hasMeta(target, migrationKey)) return 0;
        }

        List<LegacyGrid> rows = new ArrayList<>();
        try (Connection source = DriverManager.getConnection("jdbc:sqlite:" + sourceDatabase.toAbsolutePath())) {
            if (!tableExists(source, table)) {
                logger.fine(() -> "Legacy grid table is absent; import skipped: " + table);
                return 0;
            }
            try (Statement statement = source.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT id, world, grid_x, grid_z, last_used_at FROM " + table)) {
            while (rs.next()) {
                rows.add(new LegacyGrid(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getLong(5)));
            }
            }
        }

        try (Connection target = open()) {
            target.setAutoCommit(false);
            try (PreparedStatement insert = target.prepareStatement("""
                    INSERT OR IGNORE INTO iw_grids
                    (id, world, grid_x, grid_z, status, occupied_by, occupied_since, last_used_at)
                    VALUES (?, ?, ?, ?, 'FREE', NULL, 0, ?)
                    """)) {
                for (LegacyGrid row : rows) {
                    insert.setString(1, row.id());
                    insert.setString(2, row.world());
                    insert.setInt(3, row.x());
                    insert.setInt(4, row.z());
                    insert.setLong(5, row.lastUsedAt());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            putMeta(target, migrationKey, Long.toString(now()));
            target.commit();
        }
        return rows.size();
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    public synchronized int recoverInterruptedInstances() throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            int recovered;
            try (PreparedStatement grids = connection.prepareStatement("""
                    UPDATE iw_grids SET status='FREE', occupied_by=NULL, occupied_since=0, last_used_at=?
                    WHERE occupied_by IN (
                        SELECT id FROM iw_instances WHERE state IN ('PREPARING','RELEASING')
                    )
                    """)) {
                grids.setLong(1, now());
                grids.executeUpdate();
            }
            try (PreparedStatement instances = connection.prepareStatement("""
                    UPDATE iw_instances SET state='FAILED', error='Recovered after plugin restart', updated_at=?
                    WHERE state IN ('PREPARING','RELEASING')
                    """)) {
                instances.setLong(1, now());
                recovered = instances.executeUpdate();
            }
            connection.commit();
            return recovered;
        }
    }

    public synchronized boolean claimReady(String instanceId, String sessionId) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                String gridId;
                try (PreparedStatement find = connection.prepareStatement(
                        "SELECT grid_id FROM iw_instances WHERE id=? AND state='READY'")) {
                    find.setString(1, instanceId);
                    try (ResultSet rows = find.executeQuery()) {
                        if (!rows.next()) {
                            connection.rollback();
                            return false;
                        }
                        gridId = rows.getString(1);
                    }
                }
                try (PreparedStatement grid = connection.prepareStatement("""
                        UPDATE iw_grids SET status='OCCUPIED', occupied_by=?, occupied_since=?
                        WHERE id=? AND status IN ('FREE','OCCUPIED')
                        AND (occupied_by IS NULL OR occupied_by=?)
                        """)) {
                    grid.setString(1, instanceId);
                    grid.setLong(2, now());
                    grid.setString(3, gridId);
                    grid.setString(4, instanceId);
                    if (grid.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                try (PreparedStatement instance = connection.prepareStatement(
                        "UPDATE iw_instances SET owner_session=?, error='', updated_at=? WHERE id=? AND state='READY'")) {
                    instance.setString(1, sessionId);
                    instance.setLong(2, now());
                    instance.setString(3, instanceId);
                    if (instance.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                connection.commit();
                return true;
            } catch (Throwable failure) {
                connection.rollback();
                if (failure instanceof SQLException sql) throw sql;
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new SQLException(failure);
            }
        }
    }

    public synchronized GridLease allocate(String instanceId, String sessionId, InstancePrepareRequest request) throws SQLException {
        InstanceGridSpec spec = request.grid();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                ensurePool(connection, spec);
                GridLease lease = findFreeGrid(connection, spec.worldName());
                if (lease == null) {
                    int count = countGrids(connection, spec.worldName());
                    if (count >= spec.maxGrids()) {
                        throw new IllegalStateException("Instance grid pool is full");
                    }
                    lease = createGrid(connection, spec, count);
                }
                long now = now();
                try (PreparedStatement occupy = connection.prepareStatement("""
                        UPDATE iw_grids SET status='OCCUPIED', occupied_by=?, occupied_since=?, last_used_at=?
                        WHERE id=? AND status='FREE'
                        """)) {
                    occupy.setString(1, instanceId);
                    occupy.setLong(2, now);
                    occupy.setLong(3, now);
                    occupy.setString(4, lease.gridId());
                    if (occupy.executeUpdate() != 1) throw new SQLException("Grid allocation race");
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO iw_instances
                        (id, namespace, template_key, schematic_name, grid_id, state, arena_radius,
                         owner_session, error, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 'PREPARING', ?, ?, '', ?, ?)
                        """)) {
                    insert.setString(1, instanceId);
                    insert.setString(2, request.namespace());
                    insert.setString(3, request.templateKey());
                    insert.setString(4, request.schematicName());
                    insert.setString(5, lease.gridId());
                    insert.setInt(6, request.arenaRadius());
                    insert.setString(7, sessionId);
                    insert.setLong(8, now);
                    insert.setLong(9, now);
                    insert.executeUpdate();
                }
                connection.commit();
                return lease;
            } catch (Throwable failure) {
                connection.rollback();
                if (failure instanceof SQLException sql) throw sql;
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new SQLException(failure);
            }
        }
    }

    public synchronized void markReady(String instanceId, InstanceBounds bounds, InstancePoint center, InstancePoint spawn) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE iw_instances SET state='READY', min_x=?, min_y=?, min_z=?, max_x=?, max_y=?, max_z=?,
                center_x=?, center_y=?, center_z=?, spawn_x=?, spawn_y=?, spawn_z=?, updated_at=?
                WHERE id=? AND state='PREPARING'
                """)) {
            statement.setInt(1, bounds.minX());
            statement.setInt(2, bounds.minY());
            statement.setInt(3, bounds.minZ());
            statement.setInt(4, bounds.maxX());
            statement.setInt(5, bounds.maxY());
            statement.setInt(6, bounds.maxZ());
            statement.setDouble(7, center.x());
            statement.setDouble(8, center.y());
            statement.setDouble(9, center.z());
            statement.setDouble(10, spawn.x());
            statement.setDouble(11, spawn.y());
            statement.setDouble(12, spawn.z());
            statement.setLong(13, now());
            statement.setString(14, instanceId);
            if (statement.executeUpdate() != 1) throw new SQLException("Instance no longer preparing: " + instanceId);
        }
    }

    public synchronized boolean markReleasing(String instanceId) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE iw_instances SET state='RELEASING', updated_at=?
                WHERE id=? AND state IN ('PREPARING','READY')
                """)) {
            statement.setLong(1, now());
            statement.setString(2, instanceId);
            return statement.executeUpdate() == 1;
        }
    }

    public synchronized void finishRelease(String instanceId, boolean failed, String error) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                String gridId = null;
                String state = null;
                try (PreparedStatement find = connection.prepareStatement(
                        "SELECT grid_id, state FROM iw_instances WHERE id=?")) {
                    find.setString(1, instanceId);
                    try (ResultSet rs = find.executeQuery()) {
                        if (rs.next()) {
                            gridId = rs.getString(1);
                            state = rs.getString(2);
                        }
                    }
                }
                if (gridId == null) {
                    connection.commit();
                    return;
                }
                if ("RELEASED".equals(state) || "FAILED".equals(state)) {
                    // Already terminal. Never free a grid that may already be reused by another instance.
                    connection.commit();
                    return;
                }
                try (PreparedStatement free = connection.prepareStatement("""
                        UPDATE iw_grids SET status='FREE', occupied_by=NULL, occupied_since=0, last_used_at=?
                        WHERE id=? AND occupied_by=?
                        """)) {
                    free.setLong(1, now());
                    free.setString(2, gridId);
                    free.setString(3, instanceId);
                    free.executeUpdate();
                }
                try (PreparedStatement finish = connection.prepareStatement("""
                        UPDATE iw_instances SET state=?, error=?, updated_at=?
                        WHERE id=? AND state NOT IN ('RELEASED','FAILED')
                        """)) {
                    finish.setString(1, failed ? "FAILED" : "RELEASED");
                    finish.setString(2, error == null ? "" : error);
                    finish.setLong(3, now());
                    finish.setString(4, instanceId);
                    finish.executeUpdate();
                }
                connection.commit();
            } catch (Throwable failure) {
                connection.rollback();
                if (failure instanceof SQLException sql) throw sql;
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new SQLException(failure);
            }
        }
    }

    public synchronized void releaseSession(String sessionId) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement grids = connection.prepareStatement("""
                    UPDATE iw_grids SET status='FREE', occupied_by=NULL, occupied_since=0, last_used_at=?
                    WHERE occupied_by IN (SELECT id FROM iw_instances WHERE owner_session=? AND state IN ('PREPARING','READY','RELEASING'))
                    """)) {
                grids.setLong(1, now());
                grids.setString(2, sessionId);
                grids.executeUpdate();
            }
            try (PreparedStatement instances = connection.prepareStatement("""
                    UPDATE iw_instances SET state='FAILED', error='Plugin disabled', updated_at=?
                    WHERE owner_session=? AND state IN ('PREPARING','READY','RELEASING')
                    """)) {
                instances.setLong(1, now());
                instances.setString(2, sessionId);
                instances.executeUpdate();
            }
            connection.commit();
        } catch (SQLException failure) {
            logger.warning("Failed to release instance session: " + failure.getMessage());
        }
    }

    public List<GridSnapshot> loadGrids() throws SQLException {
        List<GridSnapshot> grids = new ArrayList<>();
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM iw_grids ORDER BY world, grid_x, grid_z")) {
            while (rs.next()) {
                grids.add(new GridSnapshot(
                        rs.getString("id"),
                        rs.getString("world"),
                        rs.getInt("grid_x"),
                        rs.getInt("grid_z"),
                        rs.getString("status"),
                        rs.getLong("occupied_since"),
                        rs.getLong("last_used_at")));
            }
        }
        return grids;
    }

    public List<InstanceSnapshot> loadInstances() throws SQLException {
        List<InstanceSnapshot> instances = new ArrayList<>();
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     SELECT i.*, g.world, g.grid_x, g.grid_z
                     FROM iw_instances i JOIN iw_grids g ON g.id=i.grid_id
                     ORDER BY i.created_at
                     """)) {
            while (rs.next()) {
                InstanceBounds bounds = rs.getObject("min_x") == null ? null : new InstanceBounds(
                        rs.getInt("min_x"), rs.getInt("min_y"), rs.getInt("min_z"),
                        rs.getInt("max_x"), rs.getInt("max_y"), rs.getInt("max_z"));
                InstancePoint center = rs.getObject("center_x") == null ? null : new InstancePoint(
                        rs.getDouble("center_x"), rs.getDouble("center_y"), rs.getDouble("center_z"), 0, 0);
                InstancePoint spawn = rs.getObject("spawn_x") == null ? null : new InstancePoint(
                        rs.getDouble("spawn_x"), rs.getDouble("spawn_y"), rs.getDouble("spawn_z"), 0, 0);
                instances.add(new InstanceSnapshot(
                        rs.getString("id"), rs.getString("namespace"), rs.getString("template_key"),
                        rs.getString("world"), rs.getString("grid_id"), rs.getInt("grid_x"), rs.getInt("grid_z"),
                        InstanceState.valueOf(rs.getString("state")),
                        bounds, center, spawn, rs.getString("error")));
            }
        }
        return instances;
    }

    public List<RecoverableInstance> loadRecoverableInstances() throws SQLException {
        List<RecoverableInstance> instances = new ArrayList<>();
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     SELECT i.*, g.world, g.grid_x, g.grid_z
                     FROM iw_instances i JOIN iw_grids g ON g.id=i.grid_id
                     WHERE i.state='READY'
                     ORDER BY i.created_at
                     """)) {
            while (rs.next()) {
                InstanceBounds bounds = new InstanceBounds(
                        rs.getInt("min_x"), rs.getInt("min_y"), rs.getInt("min_z"),
                        rs.getInt("max_x"), rs.getInt("max_y"), rs.getInt("max_z"));
                InstancePoint center = new InstancePoint(
                        rs.getDouble("center_x"), rs.getDouble("center_y"), rs.getDouble("center_z"), 0, 0);
                InstancePoint spawn = new InstancePoint(
                        rs.getDouble("spawn_x"), rs.getDouble("spawn_y"), rs.getDouble("spawn_z"), 0, 0);
                InstanceSnapshot snapshot = new InstanceSnapshot(
                        rs.getString("id"), rs.getString("namespace"), rs.getString("template_key"),
                        rs.getString("world"), rs.getString("grid_id"), rs.getInt("grid_x"), rs.getInt("grid_z"),
                        InstanceState.READY, bounds, center, spawn, rs.getString("error"));
                instances.add(new RecoverableInstance(snapshot, rs.getString("schematic_name"),
                        Math.max(16, rs.getInt("arena_radius"))));
            }
        }
        return List.copyOf(instances);
    }

    public Map<String, Integer> loadPoolCaps() throws SQLException {
        Map<String, Integer> caps = new LinkedHashMap<>();
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT world, max_grids FROM iw_pools")) {
            while (rs.next()) {
                caps.put(rs.getString(1), rs.getInt(2));
            }
        }
        return caps;
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private void ensurePool(Connection connection, InstanceGridSpec spec) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT spacing, max_grids FROM iw_pools WHERE world=?")) {
            select.setString(1, spec.worldName());
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    int existingCount = countGrids(connection, spec.worldName());
                    if (spec.maxGrids() < existingCount) {
                        throw new IllegalStateException(
                                "maxGrids cannot be lower than the existing grid count for " + spec.worldName());
                    }
                    if (rs.getInt(1) != spec.spacing() || rs.getInt(2) != spec.maxGrids()) {
                        try (PreparedStatement update = connection.prepareStatement(
                                "UPDATE iw_pools SET spacing=?, max_grids=? WHERE world=?")) {
                            update.setInt(1, spec.spacing());
                            update.setInt(2, spec.maxGrids());
                            update.setString(3, spec.worldName());
                            update.executeUpdate();
                        }
                    }
                    return;
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO iw_pools(world, spacing, max_grids, created_at) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, spec.worldName());
            insert.setInt(2, spec.spacing());
            insert.setInt(3, spec.maxGrids());
            insert.setLong(4, now());
            insert.executeUpdate();
        }
    }

    private GridLease findFreeGrid(Connection connection, String world) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, world, grid_x, grid_z FROM iw_grids
                WHERE world=? AND status='FREE' ORDER BY last_used_at, grid_x, grid_z LIMIT 1
                """)) {
            statement.setString(1, world);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                return new GridLease(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4));
            }
        }
    }

    private int countGrids(Connection connection, String world) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM iw_grids WHERE world=?")) {
            statement.setString(1, world);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private GridLease createGrid(Connection connection, InstanceGridSpec spec, int existingCount) throws SQLException {
        int side = (int) Math.ceil(Math.sqrt(spec.maxGrids()));
        for (int index = existingCount; index < spec.maxGrids(); index++) {
            int gridX = (index % side) * spec.spacing();
            int gridZ = (index / side) * spec.spacing();
            String id = "IWG-" + UUID.randomUUID().toString().substring(0, 12);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO iw_grids(id, world, grid_x, grid_z, status, last_used_at)
                    VALUES (?, ?, ?, ?, 'FREE', 0)
                    """)) {
                insert.setString(1, id);
                insert.setString(2, spec.worldName());
                insert.setInt(3, gridX);
                insert.setInt(4, gridZ);
                insert.executeUpdate();
                return new GridLease(id, spec.worldName(), gridX, gridZ);
            } catch (SQLException failure) {
                String message = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase();
                if (!message.contains("unique")) throw failure;
            }
        }
        throw new IllegalStateException("Instance grid pool is full");
    }

    private boolean hasMeta(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM iw_meta WHERE key=?")) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void putMeta(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR REPLACE INTO iw_meta(key, value) VALUES (?, ?)")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    public record GridLease(String gridId, String worldName, int centerX, int centerZ) {}

    public record RecoverableInstance(InstanceSnapshot snapshot, String schematicName, int arenaRadius) {}

    private record LegacyGrid(String id, String world, int x, int z, long lastUsedAt) {}
}
