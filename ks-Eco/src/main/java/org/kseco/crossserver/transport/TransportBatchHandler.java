package org.kseco.crossserver.transport;

import java.util.concurrent.CompletionStage;

/**
 * Receives immutable events on the server thread. Implementations must not block that thread; asynchronous
 * settlement can be represented by the returned stage. The cursor is committed only after ACKNOWLEDGE.
 */
@FunctionalInterface
public interface TransportBatchHandler {
    CompletionStage<BatchDisposition> onBatch(PollBatch batch);

    /** Called on the server thread for polling, callback, or cursor-commit failures. */
    default void onTransportFailure(Throwable failure) {
    }
}
