package org.kseco.crossserver;

import org.kseco.database.DatabaseDialect;

import java.util.Objects;

/**
 * Fail-closed startup gate for the cross-server runtime.
 *
 * <p>Enabling the runtime is safe only after database-authoritative mutation paths, derived cache invalidation,
 * and non-idempotent cluster tasks have all been wired by the plugin integration.</p>
 */
public final class CrossServerRuntimeGate {
    private CrossServerRuntimeGate() {}

    public static Decision evaluate(
            boolean requested,
            DatabaseDialect dialect,
            boolean mutationWiringComplete
    ) {
        DatabaseDialect checkedDialect = Objects.requireNonNull(dialect, "dialect");
        if (!requested) {
            return new Decision(Status.DISABLED,
                    "cross-server runtime is disabled by configuration");
        }
        if (!checkedDialect.sharedDatabaseCapable()) {
            return new Decision(Status.REJECTED_DATABASE,
                    "cross-server runtime requires a shared MySQL, MariaDB or PostgreSQL database; detected "
                            + checkedDialect);
        }
        if (!mutationWiringComplete) {
            return new Decision(Status.REJECTED_INCOMPLETE_WIRING,
                    "cross-server runtime cannot start until shared mutations, derived caches and cluster tasks "
                            + "have complete database-authoritative wiring");
        }
        return new Decision(Status.READY, "cross-server runtime prerequisites are satisfied");
    }

    public enum Status {
        DISABLED,
        REJECTED_DATABASE,
        REJECTED_INCOMPLETE_WIRING,
        READY
    }

    public record Decision(Status status, String message) {
        public Decision {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(message, "message");
        }

        public boolean pluginStartupAllowed() {
            return status == Status.DISABLED || status == Status.READY;
        }

        public boolean runtimeEnabled() {
            return status == Status.READY;
        }
    }
}
