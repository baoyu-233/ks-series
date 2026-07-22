package org.kseco.crossserver.transport;

/**
 * Blocking JDBC-facing SPI. Every method is called on the supplied database executor. Implementations must close
 * their transaction before returning; in particular, no transaction may span the server-thread batch callback.
 */
public interface DatabaseTransportStore {
    TransportPublishReceipt publish(TransportEvent event) throws Exception;

    /** Returns the persisted cursor, creating or treating a missing cursor as {@link PollCursor#initial()}. */
    PollCursor loadCursor(String consumerId) throws Exception;

    /** Returns a fully detached immutable batch ordered by the store's monotonic publish sequence. */
    PollBatch poll(PollRequest request) throws Exception;

    /** Atomically advances expected to next. False indicates another poller changed the consumer cursor. */
    boolean advanceCursor(String consumerId, PollCursor expected, PollCursor next) throws Exception;
}
