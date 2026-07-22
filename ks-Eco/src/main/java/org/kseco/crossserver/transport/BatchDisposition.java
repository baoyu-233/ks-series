package org.kseco.crossserver.transport;

/** Whole-batch acknowledgement decision. Retried batches rely on event-id idempotency. */
public enum BatchDisposition {
    ACKNOWLEDGE,
    RETRY
}
