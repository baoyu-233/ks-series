package org.kseries.rpg.api;

/** Read-only season runtime status. Implementations must not perform I/O from {@link #status()}. */
public interface RpgSeasonStatusApi {
    RuntimeStatus status();

    enum RuntimeState {
        DISABLED,
        STARTING,
        READY,
        FAILED,
        STOPPED
    }

    record RuntimeStatus(
            boolean configuredEnabled,
            boolean serviceEnabled,
            RuntimeState state,
            String detail,
            String storagePath
    ) {
        public RuntimeStatus {
            if (state == null) throw new IllegalArgumentException("state must not be null");
            detail = detail == null ? "" : detail;
            storagePath = storagePath == null ? "" : storagePath;
        }
    }
}
