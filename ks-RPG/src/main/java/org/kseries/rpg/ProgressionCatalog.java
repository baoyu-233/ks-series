package org.kseries.rpg;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.kseries.rpg.api.RpgProgressionApi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
                String normalized = normalize(id);
                ConfigurationSection section = proofRoot.getConfigurationSection(id);
                if (normalized == null || section == null) continue;
                proofs.put(normalized, new RpgProgressionApi.ProofDefinition(normalized,
                        section.getString("display", normalized)));
            }
        }

        Map<String, RpgProgressionApi.GateDefinition> gates = new LinkedHashMap<>();
        ConfigurationSection gateRoot = config.getConfigurationSection("gates");
        if (gateRoot != null) {
            for (String id : gateRoot.getKeys(false)) {
                String normalized = normalize(id);
                ConfigurationSection section = gateRoot.getConfigurationSection(id);
                if (normalized == null || section == null) continue;
                List<String> requiredProofs = section.getStringList("required-proofs").stream()
                        .map(ProgressionCatalog::normalize)
                        .filter(value -> value != null && proofs.containsKey(value))
                        .toList();
                gates.put(normalized, new RpgProgressionApi.GateDefinition(normalized,
                        section.getString("display", normalized), requiredProofs));
            }
        }
        return new ProgressionCatalog(proofs, gates);
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
