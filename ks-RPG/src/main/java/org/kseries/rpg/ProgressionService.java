package org.kseries.rpg;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.kseries.rpg.api.RpgProgressionApi;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class ProgressionService implements RpgProgressionApi {
    private static final String PROOF_PREFIX = "proof.";

    private final KsRpg plugin;
    private ProgressionCatalog catalog;

    ProgressionService(KsRpg plugin) {
        this.plugin = plugin;
    }

    void replaceCatalog(ProgressionCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public Optional<ProofDefinition> proof(String proofId) {
        return catalog.proof(proofId);
    }

    @Override
    public Optional<GateDefinition> gate(String gateId) {
        return catalog.gate(gateId);
    }

    @Override
    public List<ProofDefinition> proofDefinitions() {
        return catalog.proofs();
    }

    @Override
    public List<GateDefinition> gateDefinitions() {
        return catalog.gates();
    }

    @Override
    public Set<String> proofs(Player player) {
        Set<String> collected = new LinkedHashSet<>();
        for (NamespacedKey key : player.getPersistentDataContainer().getKeys()) {
            if (!key.getNamespace().equals(plugin.getName().toLowerCase(Locale.ROOT))) continue;
            String value = key.getKey();
            if (!value.startsWith(PROOF_PREFIX)) continue;
            String proofId = value.substring(PROOF_PREFIX.length());
            if (catalog.proof(proofId).isPresent()
                    && player.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
                collected.add(proofId);
            }
        }
        return collected.stream().sorted(Comparator.naturalOrder())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean hasProof(Player player, String proofId) {
        String normalized = ProgressionCatalog.normalize(proofId);
        return normalized != null && catalog.proof(normalized).isPresent()
                && player.getPersistentDataContainer().has(proofKey(normalized), PersistentDataType.BYTE);
    }

    @Override
    public ProofMutation grantProof(Player player, String proofId) {
        String normalized = ProgressionCatalog.normalize(proofId);
        if (normalized == null) return ProofMutation.INVALID_PROOF_ID;
        if (catalog.proof(normalized).isEmpty()) return ProofMutation.UNKNOWN_PROOF;
        PersistentDataContainer data = player.getPersistentDataContainer();
        NamespacedKey key = proofKey(normalized);
        if (data.has(key, PersistentDataType.BYTE)) return ProofMutation.ALREADY_GRANTED;
        data.set(key, PersistentDataType.BYTE, (byte) 1);
        return ProofMutation.GRANTED;
    }

    @Override
    public ProofMutation revokeProof(Player player, String proofId) {
        String normalized = ProgressionCatalog.normalize(proofId);
        if (normalized == null) return ProofMutation.INVALID_PROOF_ID;
        if (catalog.proof(normalized).isEmpty()) return ProofMutation.UNKNOWN_PROOF;
        PersistentDataContainer data = player.getPersistentDataContainer();
        NamespacedKey key = proofKey(normalized);
        if (!data.has(key, PersistentDataType.BYTE)) return ProofMutation.NOT_PRESENT;
        data.remove(key);
        return ProofMutation.REVOKED;
    }

    @Override
    public GateCheck checkGate(Player player, String gateId) {
        Optional<GateDefinition> gate = catalog.gate(gateId);
        if (gate.isEmpty()) return new GateCheck(gateId, false, false, List.of());
        List<String> missing = gate.get().requiredProofs().stream()
                .filter(proofId -> !hasProof(player, proofId))
                .toList();
        return new GateCheck(gate.get().id(), true, missing.isEmpty(), missing);
    }

    private NamespacedKey proofKey(String proofId) {
        return new NamespacedKey(plugin, PROOF_PREFIX + proofId);
    }
}
