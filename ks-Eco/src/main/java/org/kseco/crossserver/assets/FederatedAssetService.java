package org.kseco.crossserver.assets;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Policy-aware federated query and exact aggregation with canonical-key deduplication. */
public final class FederatedAssetService {
    private final FederatedAssetRepository repository;
    private final Supplier<RuntimeSettings> runtimeSettings;

    public FederatedAssetService(FederatedAssetRepository repository, FederatedPolicyManager policies,
                                 long staleAfterMillis, long offlineAfterMillis) {
        this.repository = Objects.requireNonNull(repository, "repository");
        FederatedPolicyManager manager = Objects.requireNonNull(policies, "policies");
        if (staleAfterMillis < 1 || offlineAfterMillis < 1) throw new IllegalArgumentException("Invalid freshness window");
        this.runtimeSettings = () -> new RuntimeSettings(true, manager.current(), staleAfterMillis, offlineAfterMillis);
    }

    public FederatedAssetService(FederatedAssetRepository repository, FederatedAssetSettingsManager settings) {
        this.repository = Objects.requireNonNull(repository, "repository");
        FederatedAssetSettingsManager manager = Objects.requireNonNull(settings, "settings");
        this.runtimeSettings = () -> {
            FederatedAssetSettings current = manager.current();
            return new RuntimeSettings(current.enabled(), current.policy(), current.staleAfterMillis(),
                    current.offlineAfterMillis());
        };
    }

    public List<AssetView> query(Query query, long now) throws SQLException {
        Objects.requireNonNull(query, "query");
        if (now < 0) throw new IllegalArgumentException("now must be non-negative");
        RuntimeSettings settings = runtimeSettings.get();
        if (!settings.enabled()) return List.of();
        var filter = new FederatedAssetRepository.Filter(query.nodeId(), query.worldId(), query.dimensionId(),
                query.assetType(), query.ownerKey());
        Map<String, AssetView> unique = new LinkedHashMap<>();
        for (FederatedAssetRepository.StoredAsset stored : repository.queryAssets(filter)) {
            FederatedAsset asset = stored.asset();
            if (!settings.policy().decide(query.capability(), asset.source()).allowed()) continue;
            if (isPropertyAsset(asset.assetType())
                    && !settings.policy().decide(FederatedCapability.PROPERTY_TRADE, asset.source()).allowed()) continue;
            boolean stale = now > asset.updatedAt() && now - asset.updatedAt() > settings.staleAfterMillis();
            boolean offline = stored.nodeLastSeenAt() == 0
                    || now > stored.nodeLastSeenAt() && now - stored.nodeLastSeenAt() > settings.offlineAfterMillis();
            if ((!query.includeStale() && stale) || (!query.includeOffline() && offline)) continue;
            AssetView candidate = new AssetView(asset, stale, offline, stored.nodeLastSeenAt());
            unique.merge(asset.canonicalKey(), candidate, FederatedAssetService::prefer);
        }
        List<AssetView> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparing(view -> view.asset().canonicalKey()));
        return List.copyOf(result);
    }

    public Aggregate aggregate(Query query, long now) throws SQLException {
        long quantity = 0;
        long valueMinor = 0;
        Map<String, Long> countsByType = new LinkedHashMap<>();
        List<AssetView> assets = query(query, now);
        for (AssetView view : assets) {
            quantity = Math.addExact(quantity, view.asset().quantity());
            valueMinor = Math.addExact(valueMinor, view.asset().valueMinor());
            countsByType.merge(view.asset().assetType(), 1L, Math::addExact);
        }
        return new Aggregate(assets.size(), quantity, valueMinor, Map.copyOf(countsByType));
    }

    public boolean canTransfer(AssetSource source, AssetSource destination) {
        RuntimeSettings settings = runtimeSettings.get();
        return settings.enabled() && settings.policy().decideTransfer(source, destination).allowed();
    }

    public Optional<FederatedSnapshot.ReadResult> readSnapshot(FederatedSnapshot.Kind kind, AssetSource source,
                                                                long now, boolean includeStale,
                                                                boolean includeOffline) throws SQLException {
        RuntimeSettings settings = runtimeSettings.get();
        if (!settings.enabled()) return Optional.empty();
        if (!settings.policy().decide(capabilityFor(kind), source).allowed()) return Optional.empty();
        Optional<FederatedSnapshot.ReadResult> result = repository.readLatest(kind, source, now,
                settings.offlineAfterMillis());
        return result.filter(value -> (includeStale || !value.stale()) && (includeOffline || !value.offline()))
                .filter(value -> value.bundle().assets().stream().noneMatch(asset -> isPropertyAsset(asset.assetType()))
                        || settings.policy().decide(FederatedCapability.PROPERTY_TRADE, source).allowed());
    }

    public List<FederatedAssetRepository.SnapshotHead> listSnapshotHeads(FederatedSnapshot.Kind kind, long now,
                                                                          boolean includeStale,
                                                                          boolean includeOffline) throws SQLException {
        RuntimeSettings settings = runtimeSettings.get();
        if (!settings.enabled()) return List.of();
        List<FederatedAssetRepository.SnapshotHead> visible = new ArrayList<>();
        for (FederatedAssetRepository.SnapshotHead head : repository.listSnapshotHeads(kind)) {
            if (!settings.policy().decide(capabilityFor(kind), head.source()).allowed()) continue;
            boolean stale = now >= head.expiresAt();
            boolean offline = head.nodeLastSeenAt() == 0 || now > head.nodeLastSeenAt()
                    && now - head.nodeLastSeenAt() > settings.offlineAfterMillis();
            if ((!includeStale && stale) || (!includeOffline && offline)) continue;
            visible.add(head);
        }
        visible.sort(Comparator.comparing(head -> head.source().stableKey()));
        return List.copyOf(visible);
    }

    private static FederatedCapability capabilityFor(FederatedSnapshot.Kind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case MAP -> FederatedCapability.MAP_VIEW;
            case PROPERTY -> FederatedCapability.PROPERTY_TRADE;
            case ASSET -> FederatedCapability.ASSET_AGGREGATE;
        };
    }

    private static boolean isPropertyAsset(String assetType) {
        return "PROPERTY".equals(assetType) || "REAL_ESTATE".equals(assetType)
                || assetType.startsWith("PROPERTY:") || assetType.startsWith("REAL_ESTATE:");
    }

    private static AssetView prefer(AssetView left, AssetView right) {
        if (left.offline() != right.offline()) return left.offline() ? right : left;
        if (left.stale() != right.stale()) return left.stale() ? right : left;
        if (left.asset().revision() != right.asset().revision()) {
            return left.asset().revision() > right.asset().revision() ? left : right;
        }
        if (left.asset().updatedAt() != right.asset().updatedAt()) {
            return left.asset().updatedAt() > right.asset().updatedAt() ? left : right;
        }
        return left.asset().source().stableKey().compareTo(right.asset().source().stableKey()) <= 0 ? left : right;
    }

    public record Query(FederatedCapability capability, String nodeId, String worldId, String dimensionId,
                        String assetType, String ownerKey, boolean includeStale, boolean includeOffline) {
        public Query {
            capability = Objects.requireNonNull(capability, "capability");
            nodeId = optionalCanonical(nodeId, "nodeId");
            worldId = optionalCanonical(worldId, "worldId");
            dimensionId = optionalCanonical(dimensionId, "dimensionId");
            assetType = assetType == null || assetType.isBlank() ? null : assetType.strip().toUpperCase(Locale.ROOT);
            ownerKey = ownerKey == null || ownerKey.isBlank() ? null : ownerKey.strip().toLowerCase(Locale.ROOT);
        }

        public static Query aggregateAll() {
            return new Query(FederatedCapability.ASSET_AGGREGATE, null, null, null, null, null, false, false);
        }

        private static String optionalCanonical(String value, String name) {
            return value == null || value.isBlank() ? null : AssetSource.canonical(value, name);
        }
    }

    public record AssetView(FederatedAsset asset, boolean stale, boolean offline, long nodeLastSeenAt) { }

    public record Aggregate(long distinctAssets, long quantity, long valueMinor, Map<String, Long> countsByType) { }

    private record RuntimeSettings(boolean enabled, FederatedAccessPolicy policy, long staleAfterMillis,
                                   long offlineAfterMillis) { }
}
