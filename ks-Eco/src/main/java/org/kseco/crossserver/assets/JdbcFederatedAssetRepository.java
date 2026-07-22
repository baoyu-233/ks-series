package org.kseco.crossserver.assets;

import org.kseco.database.BusinessSchemaDialect;
import org.kseco.database.DatabaseDialect;
import org.kseco.database.PortableSqlMutation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Portable JDBC implementation. All methods are blocking and must run on the database lane. */
public final class JdbcFederatedAssetRepository implements FederatedAssetRepository {
    private final ConnectionFactory connections;

    public JdbcFederatedAssetRepository(ConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Override
    public void initialize() throws SQLException {
        try (Connection connection = connections.open()) {
            DatabaseDialect dialect = BusinessSchemaDialect.detect(connection);
            String binary = BusinessSchemaDialect.binaryType(dialect);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS ks_eco_fed_nodes ("
                        + "node_id VARCHAR(128) PRIMARY KEY,boot_id VARCHAR(128) NOT NULL,last_seen_at BIGINT NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS ks_eco_fed_snapshots ("
                        + "snapshot_id VARCHAR(128) PRIMARY KEY,snapshot_kind VARCHAR(32) NOT NULL,node_id VARCHAR(128) NOT NULL,"
                        + "world_id VARCHAR(128) NOT NULL,dimension_id VARCHAR(128) NOT NULL,revision BIGINT NOT NULL,"
                        + "codec VARCHAR(32) NOT NULL,media_type VARCHAR(128) NOT NULL,fragment_count INT NOT NULL,"
                        + "compressed_bytes BIGINT NOT NULL,uncompressed_bytes BIGINT NOT NULL,sha256 VARCHAR(64) NOT NULL,"
                        + "produced_at BIGINT NOT NULL,expires_at BIGINT NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS ks_eco_fed_snapshot_heads ("
                        + "snapshot_kind VARCHAR(32) NOT NULL,node_id VARCHAR(128) NOT NULL,world_id VARCHAR(128) NOT NULL,"
                        + "dimension_id VARCHAR(128) NOT NULL,revision BIGINT NOT NULL,snapshot_id VARCHAR(128) NOT NULL,"
                        + "sha256 VARCHAR(64) NOT NULL,updated_at BIGINT NOT NULL,"
                        + "PRIMARY KEY(snapshot_kind,node_id,world_id,dimension_id))");
                statement.execute("CREATE TABLE IF NOT EXISTS ks_eco_fed_snapshot_fragments ("
                        + "snapshot_id VARCHAR(128) NOT NULL,fragment_no INT NOT NULL,payload " + binary + " NOT NULL,"
                        + "PRIMARY KEY(snapshot_id,fragment_no))");
                statement.execute("CREATE TABLE IF NOT EXISTS ks_eco_fed_assets ("
                        + "row_id VARCHAR(191) PRIMARY KEY,snapshot_kind VARCHAR(32) NOT NULL,snapshot_id VARCHAR(128) NOT NULL,"
                        + "canonical_key VARCHAR(191) NOT NULL,source_asset_id VARCHAR(191) NOT NULL,asset_type VARCHAR(64) NOT NULL,"
                        + "owner_key VARCHAR(191) NOT NULL,node_id VARCHAR(128) NOT NULL,world_id VARCHAR(128) NOT NULL,"
                        + "dimension_id VARCHAR(128) NOT NULL,quantity BIGINT NOT NULL,value_minor BIGINT NOT NULL,"
                        + "revision BIGINT NOT NULL,updated_at BIGINT NOT NULL)");
            }
            BusinessSchemaDialect.createUniqueIndexIfMissing(connection, "uq_fed_snapshot_revision",
                    "ks_eco_fed_snapshots", "snapshot_kind", "node_id", "world_id", "dimension_id", "revision");
            BusinessSchemaDialect.createIndexIfMissing(connection, "idx_fed_snapshot_expiry",
                    "ks_eco_fed_snapshots", "expires_at");
            BusinessSchemaDialect.createIndexIfMissing(connection, "idx_fed_assets_scope",
                    "ks_eco_fed_assets", "node_id", "world_id", "dimension_id", "asset_type");
            BusinessSchemaDialect.createIndexIfMissing(connection, "idx_fed_assets_canonical",
                    "ks_eco_fed_assets", "canonical_key", "updated_at");
        }
    }

    @Override
    public void heartbeat(String nodeId, String bootId, long observedAt) throws SQLException {
        nodeId = AssetSource.canonical(nodeId, "nodeId");
        bootId = requireText(bootId, "bootId", 128);
        if (observedAt < 0) throw new IllegalArgumentException("observedAt must be non-negative");
        try (Connection connection = connections.open()) {
            upsertNode(connection, nodeId, bootId, observedAt);
        }
    }

    @Override
    public PublishResult publish(FederatedSnapshot.Bundle bundle, String bootId) throws SQLException {
        Objects.requireNonNull(bundle, "bundle");
        requireText(bootId, "bootId", 128);
        try (Connection connection = connections.open()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                FederatedSnapshot.Metadata metadata = bundle.metadata();
                upsertNode(connection, metadata.source().nodeId(), bootId, metadata.producedAt());
                PublishResult reservation = reserveHead(connection, metadata);
                if (reservation != PublishResult.PUBLISHED) {
                    connection.rollback();
                    return reservation;
                }
                insertSnapshot(connection, metadata);
                insertFragments(connection, bundle);
                replaceAssets(connection, bundle);
                connection.commit();
                return PublishResult.PUBLISHED;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        }
    }

    @Override
    public Optional<FederatedSnapshot.ReadResult> readLatest(FederatedSnapshot.Kind kind, AssetSource source,
                                                              long now, long offlineAfterMillis) throws SQLException {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        if (now < 0 || offlineAfterMillis < 1) throw new IllegalArgumentException("Invalid read clock");
        String sql = "SELECT s.snapshot_id,s.revision,s.codec,s.media_type,s.fragment_count,s.compressed_bytes,"
                + "s.uncompressed_bytes,s.sha256,s.produced_at,s.expires_at,COALESCE(n.last_seen_at,0) "
                + "FROM ks_eco_fed_snapshot_heads h JOIN ks_eco_fed_snapshots s ON s.snapshot_id=h.snapshot_id "
                + "LEFT JOIN ks_eco_fed_nodes n ON n.node_id=s.node_id WHERE h.snapshot_kind=? AND h.node_id=? "
                + "AND h.world_id=? AND h.dimension_id=?";
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, kind.id());
            bindSource(statement, 2, source);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                var metadata = new FederatedSnapshot.Metadata(row.getString(1), kind, source, row.getLong(2),
                        row.getString(3), row.getString(4), row.getInt(5), row.getLong(6), row.getLong(7),
                        row.getString(8), row.getLong(9), row.getLong(10));
                long lastSeen = row.getLong(11);
                List<FederatedSnapshot.Fragment> fragments = readFragments(connection, metadata.snapshotId());
                List<FederatedAsset> assets = readSnapshotAssets(connection, metadata.snapshotId());
                var bundle = new FederatedSnapshot.Bundle(metadata, fragments, assets);
                boolean stale = now >= metadata.expiresAt();
                boolean offline = lastSeen == 0 || now > lastSeen && now - lastSeen > offlineAfterMillis;
                return Optional.of(new FederatedSnapshot.ReadResult(bundle, stale, offline, lastSeen));
            }
        }
    }

    @Override
    public List<StoredAsset> queryAssets(Filter filter) throws SQLException {
        filter = filter == null ? new Filter(null, null, null, null, null) : filter;
        StringBuilder sql = new StringBuilder("SELECT a.canonical_key,a.source_asset_id,a.asset_type,a.owner_key,"
                + "a.quantity,a.value_minor,a.revision,a.updated_at,a.node_id,a.world_id,a.dimension_id,"
                + "COALESCE(n.last_seen_at,0) FROM ks_eco_fed_assets a LEFT JOIN ks_eco_fed_nodes n ON n.node_id=a.node_id WHERE 1=1");
        List<String> parameters = new ArrayList<>();
        appendFilter(sql, parameters, "a.node_id", filter.nodeId());
        appendFilter(sql, parameters, "a.world_id", filter.worldId());
        appendFilter(sql, parameters, "a.dimension_id", filter.dimensionId());
        appendFilter(sql, parameters, "a.asset_type", filter.assetType());
        appendFilter(sql, parameters, "a.owner_key", filter.ownerKey());
        sql.append(" ORDER BY a.canonical_key,a.updated_at DESC,a.revision DESC,a.node_id");
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < parameters.size(); index++) statement.setString(index + 1, parameters.get(index));
            List<StoredAsset> result = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    AssetSource source = new AssetSource(row.getString(9), row.getString(10), row.getString(11));
                    FederatedAsset asset = new FederatedAsset(row.getString(1), row.getString(2), row.getString(3),
                            row.getString(4), row.getLong(5), row.getLong(6), row.getLong(7), row.getLong(8), source);
                    result.add(new StoredAsset(asset, row.getLong(12)));
                }
            }
            return List.copyOf(result);
        }
    }

    @Override
    public List<SnapshotHead> listSnapshotHeads(FederatedSnapshot.Kind kind) throws SQLException {
        Objects.requireNonNull(kind, "kind");
        String sql = "SELECT h.node_id,h.world_id,h.dimension_id,h.snapshot_id,h.revision,"
                + "s.produced_at,s.expires_at,COALESCE(n.last_seen_at,0) "
                + "FROM ks_eco_fed_snapshot_heads h JOIN ks_eco_fed_snapshots s ON s.snapshot_id=h.snapshot_id "
                + "LEFT JOIN ks_eco_fed_nodes n ON n.node_id=h.node_id WHERE h.snapshot_kind=? "
                + "ORDER BY h.node_id,h.world_id,h.dimension_id";
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, kind.id());
            List<SnapshotHead> result = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    result.add(new SnapshotHead(kind, new AssetSource(row.getString(1), row.getString(2), row.getString(3)),
                            row.getString(4), row.getLong(5), row.getLong(6), row.getLong(7), row.getLong(8)));
                }
            }
            return List.copyOf(result);
        }
    }

    private PublishResult reserveHead(Connection connection, FederatedSnapshot.Metadata metadata) throws SQLException {
        String update = "UPDATE ks_eco_fed_snapshot_heads SET revision=?,snapshot_id=?,sha256=?,updated_at=? "
                + "WHERE snapshot_kind=? AND node_id=? AND world_id=? AND dimension_id=? AND revision<?";
        try (PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setLong(1, metadata.revision()); statement.setString(2, metadata.snapshotId());
            statement.setString(3, metadata.sha256()); statement.setLong(4, metadata.producedAt());
            bindHeadKey(statement, 5, metadata); statement.setLong(9, metadata.revision());
            if (statement.executeUpdate() == 1) return PublishResult.PUBLISHED;
        }
        Head current = readHead(connection, metadata);
        if (current == null) {
            boolean inserted = PortableSqlMutation.insertIfAbsent(connection,
                    "SELECT 1 FROM ks_eco_fed_snapshot_heads WHERE snapshot_kind=? AND node_id=? AND world_id=? AND dimension_id=?",
                    exists -> bindHeadKey(exists, 1, metadata),
                    "INSERT INTO ks_eco_fed_snapshot_heads(snapshot_kind,node_id,world_id,dimension_id,revision,"
                            + "snapshot_id,sha256,updated_at) VALUES (?,?,?,?,?,?,?,?)",
                    insert -> bindHead(insert, metadata));
            if (inserted) return PublishResult.PUBLISHED;
            current = readHead(connection, metadata);
        }
        if (current != null && current.revision() == metadata.revision()
                && current.snapshotId().equals(metadata.snapshotId()) && current.sha256().equals(metadata.sha256())) {
            return PublishResult.ALREADY_PUBLISHED;
        }
        return PublishResult.STALE_REJECTED;
    }

    private void insertSnapshot(Connection connection, FederatedSnapshot.Metadata metadata) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO ks_eco_fed_snapshots "
                + "(snapshot_id,snapshot_kind,node_id,world_id,dimension_id,revision,codec,media_type,fragment_count,"
                + "compressed_bytes,uncompressed_bytes,sha256,produced_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, metadata.snapshotId()); statement.setString(2, metadata.kind().id());
            statement.setString(3, metadata.source().nodeId()); statement.setString(4, metadata.source().worldId());
            statement.setString(5, metadata.source().dimensionId()); statement.setLong(6, metadata.revision());
            statement.setString(7, metadata.codec()); statement.setString(8, metadata.mediaType());
            statement.setInt(9, metadata.fragmentCount()); statement.setLong(10, metadata.compressedBytes());
            statement.setLong(11, metadata.uncompressedBytes()); statement.setString(12, metadata.sha256());
            statement.setLong(13, metadata.producedAt()); statement.setLong(14, metadata.expiresAt());
            statement.executeUpdate();
        }
    }

    private void insertFragments(Connection connection, FederatedSnapshot.Bundle bundle) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ks_eco_fed_snapshot_fragments(snapshot_id,fragment_no,payload) VALUES (?,?,?)")) {
            for (FederatedSnapshot.Fragment fragment : bundle.fragments()) {
                statement.setString(1, bundle.metadata().snapshotId()); statement.setInt(2, fragment.index());
                statement.setBytes(3, fragment.bytes()); statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void replaceAssets(Connection connection, FederatedSnapshot.Bundle bundle) throws SQLException {
        FederatedSnapshot.Metadata metadata = bundle.metadata();
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM ks_eco_fed_assets WHERE snapshot_kind=? "
                + "AND node_id=? AND world_id=? AND dimension_id=?")) {
            delete.setString(1, metadata.kind().id()); bindSource(delete, 2, metadata.source()); delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO ks_eco_fed_assets "
                + "(row_id,snapshot_kind,snapshot_id,canonical_key,source_asset_id,asset_type,owner_key,node_id,world_id,"
                + "dimension_id,quantity,value_minor,revision,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (FederatedAsset asset : bundle.assets()) {
                insert.setString(1, rowId(metadata.kind(), asset)); insert.setString(2, metadata.kind().id());
                insert.setString(3, metadata.snapshotId()); insert.setString(4, asset.canonicalKey());
                insert.setString(5, asset.sourceAssetId()); insert.setString(6, asset.assetType());
                insert.setString(7, asset.ownerKey()); insert.setString(8, asset.source().nodeId());
                insert.setString(9, asset.source().worldId()); insert.setString(10, asset.source().dimensionId());
                insert.setLong(11, asset.quantity()); insert.setLong(12, asset.valueMinor());
                insert.setLong(13, asset.revision()); insert.setLong(14, asset.updatedAt()); insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private List<FederatedSnapshot.Fragment> readFragments(Connection connection, String snapshotId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT fragment_no,payload FROM "
                + "ks_eco_fed_snapshot_fragments WHERE snapshot_id=? ORDER BY fragment_no")) {
            statement.setString(1, snapshotId);
            List<FederatedSnapshot.Fragment> result = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) result.add(new FederatedSnapshot.Fragment(row.getInt(1), row.getBytes(2)));
            }
            return result;
        }
    }

    private List<FederatedAsset> readSnapshotAssets(Connection connection, String snapshotId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT canonical_key,source_asset_id,asset_type,"
                + "owner_key,quantity,value_minor,revision,updated_at,node_id,world_id,dimension_id FROM "
                + "ks_eco_fed_assets WHERE snapshot_id=? ORDER BY canonical_key")) {
            statement.setString(1, snapshotId);
            List<FederatedAsset> result = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) result.add(new FederatedAsset(row.getString(1), row.getString(2), row.getString(3),
                        row.getString(4), row.getLong(5), row.getLong(6), row.getLong(7), row.getLong(8),
                        new AssetSource(row.getString(9), row.getString(10), row.getString(11))));
            }
            return result;
        }
    }

    private void upsertNode(Connection connection, String nodeId, String bootId, long observedAt) throws SQLException {
        PortableSqlMutation.upsert(connection,
                "UPDATE ks_eco_fed_nodes SET boot_id=?,last_seen_at=? WHERE node_id=?",
                update -> { update.setString(1, bootId); update.setLong(2, observedAt); update.setString(3, nodeId); },
                "INSERT INTO ks_eco_fed_nodes(node_id,boot_id,last_seen_at) VALUES (?,?,?)",
                insert -> { insert.setString(1, nodeId); insert.setString(2, bootId); insert.setLong(3, observedAt); });
    }

    private Head readHead(Connection connection, FederatedSnapshot.Metadata metadata) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT revision,snapshot_id,sha256 FROM "
                + "ks_eco_fed_snapshot_heads WHERE snapshot_kind=? AND node_id=? AND world_id=? AND dimension_id=?")) {
            bindHeadKey(statement, 1, metadata);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? new Head(row.getLong(1), row.getString(2), row.getString(3)) : null;
            }
        }
    }

    private static void bindHead(PreparedStatement statement, FederatedSnapshot.Metadata metadata) throws SQLException {
        bindHeadKey(statement, 1, metadata); statement.setLong(5, metadata.revision());
        statement.setString(6, metadata.snapshotId()); statement.setString(7, metadata.sha256());
        statement.setLong(8, metadata.producedAt());
    }

    private static void bindHeadKey(PreparedStatement statement, int start, FederatedSnapshot.Metadata metadata)
            throws SQLException {
        statement.setString(start, metadata.kind().id()); statement.setString(start + 1, metadata.source().nodeId());
        statement.setString(start + 2, metadata.source().worldId());
        statement.setString(start + 3, metadata.source().dimensionId());
    }

    private static void bindSource(PreparedStatement statement, int start, AssetSource source) throws SQLException {
        statement.setString(start, source.nodeId()); statement.setString(start + 1, source.worldId());
        statement.setString(start + 2, source.dimensionId());
    }

    private static void appendFilter(StringBuilder sql, List<String> parameters, String column, String value) {
        if (value == null || value.isBlank()) return;
        sql.append(" AND ").append(column).append("=?"); parameters.add(value);
    }

    private static String rowId(FederatedSnapshot.Kind kind, FederatedAsset asset) {
        String identity = kind.id() + ":" + asset.source().stableKey() + ":" + asset.sourceAssetId();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "fa:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String requireText(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("Invalid " + name);
        return value;
    }

    private record Head(long revision, String snapshotId, String sha256) { }

    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open() throws SQLException;
    }
}
