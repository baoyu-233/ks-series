package org.kseries.rpg.api;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Stable server-thread API for RPG integrations. Obtain it from Bukkit's ServicesManager.
 * Implementations must never be invoked from asynchronous tasks.
 */
public interface RpgProgressionApi {
    Optional<ProofDefinition> proof(String proofId);

    Optional<GateDefinition> gate(String gateId);

    List<ProofDefinition> proofDefinitions();

    List<GateDefinition> gateDefinitions();

    Set<String> proofs(Player player);

    boolean hasProof(Player player, String proofId);

    ProofMutation grantProof(Player player, String proofId);

    ProofMutation revokeProof(Player player, String proofId);

    GateCheck checkGate(Player player, String gateId);

    record ProofDefinition(String id, String display) { }

    record GateDefinition(String id, String display, List<String> requiredProofs) { }

    record GateCheck(String gateId, boolean exists, boolean satisfied, List<String> missingProofs) { }

    enum ProofMutation {
        GRANTED,
        ALREADY_GRANTED,
        REVOKED,
        NOT_PRESENT,
        UNKNOWN_PROOF,
        INVALID_PROOF_ID
    }
}
