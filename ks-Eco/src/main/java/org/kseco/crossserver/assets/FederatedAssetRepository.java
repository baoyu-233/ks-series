package org.kseco.crossserver.assets;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Database-lane-only repository for shared node, snapshot and asset projections. */
public interface FederatedAssetRepository {
    void initialize() throws SQLException;

    void heartbeat(String nodeId, String bootId, long observedAt) throws SQLException;

    PublishResult publish(FederatedSnapshot.Bundle bundle, String bootId) throws SQLException;

    Optional<FederatedSnapshot.ReadResult> readLatest(FederatedSnapshot.Kind kind, AssetSource source,
                                                       long now, long offlineAfterMillis) throws SQLException;

    List<StoredAsset> queryAssets(Filter filter) throws SQLException;

    List<SnapshotHead> listSnapshotHeads(FederatedSnapshot.Kind kind) throws SQLException;

    enum PublishResult { PUBLISHED, ALREADY_PUBLISHED, STALE_REJECTED }

    record Filter(String nodeId, String worldId, String dimensionId, String assetType, String ownerKey) { }

    record StoredAsset(FederatedAsset asset, long nodeLastSeenAt) { }

    record SnapshotHead(FederatedSnapshot.Kind kind, AssetSource source, String snapshotId, long revision,
                        long producedAt, long expiresAt, long nodeLastSeenAt) { }
}
