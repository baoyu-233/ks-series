package org.kseries.rpg;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.kseries.rpg.api.RpgProgressionApi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class ProgressionCatalog {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");

    private final Map<String, RpgProgressionApi.ProofDefinition> proofs;
    private final Map<String, RpgProgressionApi.GateDefinition> gates;

    private ProgressionCatalog(Map<String, RpgProgressionApi.ProofDefinition> proofs,
                               Map<String, RpgProgressionApi.GateDefinition> gates) {
        this.proofs = Map.copyOf(proofs);
        this.gates = Map.copyOf(gates);
    }

    static ProgressionCatalog load(FileConfiguration config) {
        Map<String, RpgProgressionApi.ProofDefinition> proofs = new LinkedHashMap<>();
        ConfigurationSection proofRoot = config.getConfigurationSection("combat-proofs");
        if (proofRoot != null) {
            for (String id : proofRoot.getKeys(false)) {
                String normalized = requireId(id, "combat proof");
                ConfigurationSection section = proofRoot.getConfigurationSection(id);
                if (section == null) throw new IllegalArgumentException("Combat proof " + id + " must be a section");
                RpgProgressionApi.ProofDefinition previous = proofs.putIfAbsent(normalized,
                        new RpgProgressionApi.ProofDefinition(normalized, section.getString("display", normalized)));
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate combat proof id after normalization: " + id);
                }
            }
        }

        Map<String, RpgProgressionApi.GateDefinition> gates = new LinkedHashMap<>();
        ConfigurationSection gateRoot = config.getConfigurationSection("gates");
        if (gateRoot != null) {
            for (String id : gateRoot.getKeys(false)) {
                String normalized = requireId(id, "proof gate");
                ConfigurationSection section = gateRoot.getConfigurationSection(id);
                if (section == null) throw new IllegalArgumentException("Proof gate " + id + " must be a section");
                List<String> requiredProofs = requiredProofs(normalized, section, proofs);
                RpgProgressionApi.GateDefinition previous = gates.putIfAbsent(normalized,
                        new RpgProgressionApi.GateDefinition(normalized,
                                section.getString("display", normalized), requiredProofs));
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate proof gate id after normalization: " + id);
                }
            }
        }
        return new ProgressionCatalog(proofs, gates);
    }

    private static List<String> requiredProofs(String gateId, ConfigurationSection section,
                                               Map<String, RpgProgressionApi.ProofDefinition> proofs) {
        if (!section.contains("required-proofs")) {
            throw new IllegalArgumentException("Proof gate " + gateId + " must declare required-proofs");
        }
        Object raw = section.get("required-proofs");
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("Proof gate " + gateId + " required-proofs must be a list");
        }
        if (list.isEmpty()) {
            throw new IllegalArgumentException("Proof gate " + gateId + " required-proofs must not be empty");
        }
        List<String> required = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object entry : list) {
            if (!(entry instanceof String configuredId) || configuredId.isBlank()) {
                throw new IllegalArgumentException("Proof gate " + gateId
                        + " required-proofs entries must be non-empty strings");
            }
            String proofId = requireId(configuredId, "required proof in gate " + gateId);
            if (!proofs.containsKey(proofId)) {
                throw new IllegalArgumentException("Proof gate " + gateId
                        + " references unknown combat proof: " + configuredId);
            }
            if (!seen.add(proofId)) {
                throw new IllegalArgumentException("Proof gate " + gateId
                        + " repeats combat proof: " + configuredId);
            }
            required.add(proofId);
        }
        return List.copyOf(required);
    }

    private static String requireId(String id, String description) {
        String normalized = normalize(id);
        if (normalized == null) throw new IllegalArgumentException("Invalid " + description + " id: " + id);
        return normalized;
    }

    Optional<RpgProgressionApi.ProofDefinition> proof(String id) {
        return Optional.ofNullable(proofs.get(normalize(id)));
    }

    Optional<RpgProgressionApi.GateDefinition> gate(String id) {
        return Optional.ofNullable(gates.get(normalize(id)));
    }

    List<RpgProgressionApi.ProofDefinition> proofs() {
        return proofs.values().stream().toList();
    }

    List<RpgProgressionApi.GateDefinition> gates() {
        return gates.values().stream().toList();
    }

    static String normalize(String id) {
        if (id == null) return null;
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        return ID.matcher(normalized).matches() ? normalized : null;
    }
}
