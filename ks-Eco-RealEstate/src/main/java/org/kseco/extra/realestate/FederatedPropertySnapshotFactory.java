package org.kseco.extra.realestate;

import com.google.gson.Gson;
import org.kseco.crossserver.assets.AssetSource;
import org.kseco.crossserver.assets.FederatedAsset;
import org.kseco.crossserver.assets.FederatedSnapshot;
import org.kseco.crossserver.assets.FederatedSnapshotPublisher;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;

/** Pure factory: consumes only frozen SQL/voxel DTOs and never touches Bukkit World state. */
final class FederatedPropertySnapshotFactory {
    private FederatedPropertySnapshotFactory() { }

    static FederatedSnapshotPublisher.PreparedSnapshot create(String nodeId, String dimensionId,
                                                               List<HouseSnapshot> houses,
                                                               long revision, long producedAt) {
        if (houses.isEmpty()) throw new IllegalArgumentException("Property snapshot must contain at least one house");
        String worldId = String.valueOf(houses.getFirst().house().get("world"));
        AssetSource source = new AssetSource(nodeId, worldId, dimensionId);
        List<FederatedAsset> assets = houses.stream().map(entry -> asset(entry.house(), source, revision, producedAt)).toList();
        List<Map<String, Object>> payloadHouses = houses.stream().map(HouseSnapshot::ready).toList();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "ks-realestate-property/v1");
        envelope.put("source", source.stableKey());
        envelope.put("revision", revision);
        envelope.put("generatedAt", producedAt);
        envelope.put("houses", payloadHouses);
        String stableId = UUID.nameUUIDFromBytes(source.stableKey()
                .getBytes(StandardCharsets.UTF_8)).toString();
        byte[] payload = new Gson().toJson(envelope).getBytes(StandardCharsets.UTF_8);
        return new FederatedSnapshotPublisher.PreparedSnapshot("property-" + stableId + '-' + revision,
                FederatedSnapshot.Kind.PROPERTY, source, revision, "application/json", payload,
                assets, producedAt);
    }

    private static FederatedAsset asset(Map<String, Object> house, AssetSource source, long revision, long producedAt) {
        String houseId = String.valueOf(house.get("id"));
        Object rawOwnerType = house.get("ownerType");
        Object rawOwnerId = house.get("ownerId");
        String ownerType = rawOwnerType == null ? "SYSTEM" : String.valueOf(rawOwnerType);
        String ownerId = rawOwnerId == null ? "unowned" : String.valueOf(rawOwnerId);
        return new FederatedAsset("property:" + houseId, houseId, "PROPERTY", ownerType + ":" + ownerId,
                1, priceMinor(house.get("showcasePrice")), revision, producedAt, source);
    }

    record HouseSnapshot(Map<String, Object> house, Map<String, Object> ready) {
        HouseSnapshot {
            // READY envelopes intentionally contain nullable optional fields (for example error=null).
            // Map.copyOf rejects null values, so freeze a defensive LinkedHashMap instead.
            house = Collections.unmodifiableMap(new LinkedHashMap<>(house));
            ready = Collections.unmodifiableMap(new LinkedHashMap<>(ready));
        }
    }

    static long priceMinor(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0) return 0;
        try {
            return BigDecimal.valueOf(number.doubleValue()).movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException failure) {
            return 0;
        }
    }
}
