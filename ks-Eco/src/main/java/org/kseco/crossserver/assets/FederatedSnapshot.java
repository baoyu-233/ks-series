package org.kseco.crossserver.assets;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Cross-node snapshot wire model. Payload fragments contain compressed immutable bytes, never live World objects. */
public final class FederatedSnapshot {
    private FederatedSnapshot() { }

    public enum Kind {
        MAP, PROPERTY, ASSET;

        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    public record Metadata(
            String snapshotId,
            Kind kind,
            AssetSource source,
            long revision,
            String codec,
            String mediaType,
            int fragmentCount,
            long compressedBytes,
            long uncompressedBytes,
            String sha256,
            long producedAt,
            long expiresAt
    ) {
        private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

        public Metadata {
            snapshotId = bounded(snapshotId, "snapshotId", 128);
            kind = Objects.requireNonNull(kind, "kind");
            source = Objects.requireNonNull(source, "source");
            codec = bounded(codec, "codec", 32).toLowerCase(Locale.ROOT);
            mediaType = bounded(mediaType, "mediaType", 128).toLowerCase(Locale.ROOT);
            sha256 = bounded(sha256, "sha256", 64).toLowerCase(Locale.ROOT);
            if (!SHA256.matcher(sha256).matches()) throw new IllegalArgumentException("Invalid sha256");
            if (revision < 0 || fragmentCount < 1 || compressedBytes < 0 || uncompressedBytes < 0
                    || producedAt < 0 || expiresAt <= producedAt) {
                throw new IllegalArgumentException("Invalid snapshot metadata");
            }
        }
    }

    public record Fragment(int index, byte[] bytes) {
        public Fragment {
            if (index < 0) throw new IllegalArgumentException("Negative fragment index");
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            if (bytes.length == 0) throw new IllegalArgumentException("Empty fragment");
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public record Bundle(Metadata metadata, List<Fragment> fragments, List<FederatedAsset> assets) {
        public Bundle {
            metadata = Objects.requireNonNull(metadata, "metadata");
            fragments = List.copyOf(Objects.requireNonNull(fragments, "fragments"));
            assets = List.copyOf(Objects.requireNonNull(assets, "assets"));
            if (fragments.size() != metadata.fragmentCount()) throw new IllegalArgumentException("Fragment count mismatch");
            long total = 0;
            for (int index = 0; index < fragments.size(); index++) {
                Fragment fragment = fragments.get(index);
                if (fragment.index() != index) throw new IllegalArgumentException("Fragments must be contiguous");
                total = Math.addExact(total, fragment.bytes().length);
            }
            if (total != metadata.compressedBytes()) throw new IllegalArgumentException("Compressed size mismatch");
            for (FederatedAsset asset : assets) {
                if (!asset.source().equals(metadata.source())) throw new IllegalArgumentException("Asset source mismatch");
            }
        }
    }

    public record ReadResult(Bundle bundle, boolean stale, boolean offline, long nodeLastSeenAt) {
        public ReadResult {
            bundle = Objects.requireNonNull(bundle, "bundle");
            if (nodeLastSeenAt < 0) throw new IllegalArgumentException("nodeLastSeenAt must be non-negative");
        }
    }

    private static String bounded(String value, String name, int max) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return normalized;
    }
}
