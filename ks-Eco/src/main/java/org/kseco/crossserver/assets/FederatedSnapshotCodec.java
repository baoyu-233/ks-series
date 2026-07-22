package org.kseco.crossserver.assets;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** GZIP + fixed-size fragment encoder for map/property payloads. */
public final class FederatedSnapshotCodec {
    public static final int FORMAT_VERSION = 1;
    public static final int MIN_FRAGMENT_BYTES = 1_024;
    public static final int MAX_FRAGMENT_BYTES = 1_048_576;

    private FederatedSnapshotCodec() { }

    public static FederatedSnapshot.Bundle encode(String snapshotId, FederatedSnapshot.Kind kind, AssetSource source,
                                                  long revision, String mediaType, byte[] payload,
                                                  List<FederatedAsset> assets, long producedAt, long ttlMillis,
                                                  int fragmentBytes) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length == 0) throw new IllegalArgumentException("Snapshot payload is empty");
        if (ttlMillis < 1 || fragmentBytes < MIN_FRAGMENT_BYTES || fragmentBytes > MAX_FRAGMENT_BYTES) {
            throw new IllegalArgumentException("Invalid TTL or fragment size");
        }
        byte[] compressed = gzip(payload);
        List<FederatedSnapshot.Fragment> fragments = new ArrayList<>();
        for (int offset = 0, index = 0; offset < compressed.length; offset += fragmentBytes, index++) {
            int length = Math.min(fragmentBytes, compressed.length - offset);
            byte[] part = new byte[length];
            System.arraycopy(compressed, offset, part, 0, length);
            fragments.add(new FederatedSnapshot.Fragment(index, part));
        }
        long expiresAt = Math.addExact(producedAt, ttlMillis);
        var metadata = new FederatedSnapshot.Metadata(snapshotId, kind, source, revision, "gzip",
                mediaType, fragments.size(), compressed.length, payload.length, sha256(payload), producedAt, expiresAt);
        return new FederatedSnapshot.Bundle(metadata, fragments, assets == null ? List.of() : assets);
    }

    public static byte[] decode(FederatedSnapshot.Bundle bundle) {
        return decode(bundle, Integer.MAX_VALUE);
    }

    /** Decodes with a hard output cap so a corrupt shared row cannot become a decompression bomb. */
    public static byte[] decode(FederatedSnapshot.Bundle bundle, int maxBytes) {
        Objects.requireNonNull(bundle, "bundle");
        if (maxBytes < 1 || bundle.metadata().uncompressedBytes() > maxBytes) {
            throw new IllegalArgumentException("Snapshot payload exceeds configured limit");
        }
        if (!"gzip".equals(bundle.metadata().codec())) throw new IllegalArgumentException("Unsupported snapshot codec");
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        for (FederatedSnapshot.Fragment fragment : bundle.fragments()) {
            compressed.writeBytes(fragment.bytes());
        }
        byte[] payload;
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed.toByteArray()));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) >= 0) {
                total = Math.addExact(total, read);
                if (total > maxBytes) throw new IllegalArgumentException("Snapshot payload exceeds configured limit");
                output.write(buffer, 0, read);
            }
            payload = output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalArgumentException("Corrupt snapshot payload", failure);
        }
        if (payload.length != bundle.metadata().uncompressedBytes()
                || !sha256(payload).equals(bundle.metadata().sha256())) {
            throw new IllegalArgumentException("Snapshot checksum mismatch");
        }
        return payload;
    }

    private static byte[] gzip(byte[] payload) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(payload);
            gzip.finish();
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to compress snapshot", failure);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
