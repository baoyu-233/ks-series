package org.kseco.crossserver.lock;

/** Local fail-closed state of a lease handle. */
public enum LeaseState {
    ACTIVE,
    LOST,
    RELEASED
}
