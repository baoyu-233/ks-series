package org.kseco.crossserver;

import java.util.UUID;

/**
 * Identifies one running ks-Eco process. A restart keeps the logical server ID
 * but receives a new instance ID, so stale heartbeats and leases are detectable.
 */
public record ServerInstance(String serverId, String instanceId) {
    public ServerInstance {
        serverId = CrossServerValidation.identifier(serverId, "serverId", 64);
        instanceId = CrossServerValidation.identifier(instanceId, "instanceId", 96);
    }

    public static ServerInstance start(String serverId) {
        return new ServerInstance(serverId, UUID.randomUUID().toString());
    }

    public String ownerId() {
        return serverId + "/" + instanceId;
    }
}
