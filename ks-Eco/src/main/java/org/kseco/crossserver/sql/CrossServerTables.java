package org.kseco.crossserver.sql;

/** Configurable table names for one cross-server namespace. */
public record CrossServerTables(String outbox, String inbox, String leases, String heartbeats) {
    public CrossServerTables {
        outbox = SqlIdentifiers.requireQualified(outbox);
        inbox = SqlIdentifiers.requireQualified(inbox);
        leases = SqlIdentifiers.requireQualified(leases);
        heartbeats = SqlIdentifiers.requireQualified(heartbeats);
    }

    public static CrossServerTables defaults() {
        return new CrossServerTables(
                "ks_crossserver_outbox",
                "ks_crossserver_inbox",
                "ks_crossserver_leases",
                "ks_crossserver_heartbeats"
        );
    }
}
