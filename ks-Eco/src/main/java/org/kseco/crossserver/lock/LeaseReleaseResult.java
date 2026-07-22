package org.kseco.crossserver.lock;

/** Result of releasing a lease. Release is idempotent at the handle boundary. */
public enum LeaseReleaseResult {
    RELEASED,
    ALREADY_RELEASED,
    LOST
}
