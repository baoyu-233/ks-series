package org.kseco.crossserver.lock;

/** A point-in-time database check; use executeFenced for protected writes. */
public enum LeaseCheckResult {
    HELD,
    LOST
}
