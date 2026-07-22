package org.kseco.crossserver.assets;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Asynchronously compresses and persists an already immutable local snapshot.
 * Callers must capture Bukkit/World state before invoking this class.
 */
public final class FederatedSnapshotPublisher {
    private final FederatedAssetRepository repository;
    private final FederatedAssetSettingsManager settings;
    private final Executor databaseExecutor;
    private final String bootId;

    public FederatedSnapshotPublisher(FederatedAssetRepository repository, FederatedAssetSettingsManager settings,
                                      Executor databaseExecutor, String bootId) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        this.bootId = Objects.requireNonNull(bootId, "bootId");
    }

    public CompletableFuture<FederatedAssetRepository.PublishResult> publishAsync(PreparedSnapshot prepared) {
        PreparedSnapshot immutable = Objects.requireNonNull(prepared, "prepared").copy();
        FederatedAssetSettings captured = settings.current();
        if (!captured.enabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Federated assets are disabled"));
        }
        if (immutable.payload().length > captured.maxSnapshotBytes()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Snapshot payload exceeds configured limit"));
        }
        return CompletableFuture.supplyAsync(() -> {
            FederatedSnapshot.Bundle bundle = FederatedSnapshotCodec.encode(immutable.snapshotId(), immutable.kind(),
                    immutable.source(), immutable.revision(), immutable.mediaType(), immutable.payload(), immutable.assets(),
                    immutable.producedAt(), captured.snapshotTtlMillis(), captured.fragmentBytes());
            try {
                return repository.publish(bundle, bootId);
            } catch (SQLException failure) {
                throw new CompletionException(failure);
            }
        }, databaseExecutor);
    }

    public record PreparedSnapshot(String snapshotId, FederatedSnapshot.Kind kind, AssetSource source, long revision,
                                   String mediaType, byte[] payload, List<FederatedAsset> assets, long producedAt) {
        public PreparedSnapshot {
            Objects.requireNonNull(snapshotId, "snapshotId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(mediaType, "mediaType");
            payload = Objects.requireNonNull(payload, "payload").clone();
            assets = List.copyOf(Objects.requireNonNull(assets, "assets"));
            if (revision < 0 || producedAt < 0) throw new IllegalArgumentException("Invalid prepared snapshot clock");
        }
        @Override public byte[] payload() { return payload.clone(); }
        private PreparedSnapshot copy() {
            return new PreparedSnapshot(snapshotId, kind, source, revision, mediaType, payload, assets, producedAt);
        }
    }
}
