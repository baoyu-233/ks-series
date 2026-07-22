package org.kseco.crossserver.transport;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Contract checks for cursor ordering and poll backoff. */
public final class PollingContractsTest {
    @Test
    void ordersMonotonicPublishSequences() {
        PollCursor first = new PollCursor(100L);
        PollCursor next = new PollCursor(101L);

        check(first.compareTo(next) < 0, "publish sequence must define cursor order");
        check(PollCursor.initial().compareTo(first) < 0, "initial cursor must sort before persisted events");
    }

    @Test
    void rejectsInvalidBatchCursorMovement() {
        PollCursor after = new PollCursor(100L);
        TransportEvent event = event("event-a", 90L, 100L, new byte[]{1}, Map.of());

        expectFailure(() -> new PollBatch(after, List.of(event), after, false, 101L));
        expectFailure(() -> new PollBatch(after, List.of(), new PollCursor(99L), false, 101L));
    }

    @Test
    void snapshotsBatchAndEventData() {
        byte[] payload = {1, 2, 3};
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("trace", "one");
        TransportEvent event = event("event-b", 90L, 100L, payload, headers);
        List<TransportEvent> mutableEvents = new ArrayList<>(List.of(event));
        PollBatch batch = new PollBatch(new PollCursor(100L), mutableEvents, new PollCursor(101L), false, 101L);

        payload[0] = 9;
        headers.put("trace", "two");
        mutableEvents.clear();
        byte[] exposed = event.payload();
        exposed[1] = 9;

        check(batch.events().size() == 1, "batch must snapshot its event list");
        check(event.payload()[0] == 1 && event.payload()[1] == 2, "event payload must be defensively copied");
        check("one".equals(event.headers().get("trace")), "event headers must be snapshotted");
        expectFailureType(UnsupportedOperationException.class, () -> batch.events().clear());
        expectFailureType(UnsupportedOperationException.class, () -> event.headers().put("new", "value"));
    }

    @Test
    void advancesAndRetainsCursors() {
        PollCursor after = new PollCursor(100L);
        PollBatch empty = PollBatch.empty(after, 101L);
        TransportEvent next = event("event-b", 90L, 100L, new byte[0], Map.of());
        PollBatch activity = new PollBatch(after, List.of(next), new PollCursor(101L), false, 101L);
        PollBatch skipped = new PollBatch(after, List.of(), new PollCursor(105L), true, 101L);

        check(empty.nextCursor().equals(after), "empty polling must retain the requested cursor");
        check(activity.nextCursor().equals(new PollCursor(101L)), "activity must expose the persisted sequence");
        check(skipped.nextCursor().equals(new PollCursor(105L)), "skip-only batches must advance safely");
    }

    @Test
    void appliesBoundedExponentialBackoff() {
        PollBackoffPolicy policy = new PollBackoffPolicy(25L, 100L, 500L, 1_000L, 5_000L, 2.0d, 0.0d);

        check(policy.delayMillis(PollBackoffPolicy.Outcome.ACTIVITY, 1, 0.5d) == 25L,
                "activity delay must remain fixed");
        check(policy.delayMillis(PollBackoffPolicy.Outcome.EMPTY, 1, 0.5d) == 100L,
                "first empty poll must use its initial delay");
        check(policy.delayMillis(PollBackoffPolicy.Outcome.EMPTY, 3, 0.5d) == 400L,
                "empty polling must back off exponentially");
        check(policy.delayMillis(PollBackoffPolicy.Outcome.EMPTY, 100, 0.5d) == 500L,
                "empty polling must cap at its maximum");
        check(policy.delayMillis(PollBackoffPolicy.Outcome.FAILURE, 4, 0.5d) == 5_000L,
                "failure polling must cap independently");
    }

    @Test
    void appliesDeterministicJitter() {
        PollBackoffPolicy policy = new PollBackoffPolicy(100L, 100L, 1_000L, 100L, 1_000L, 2.0d, 0.20d);

        check(policy.delayMillis(PollBackoffPolicy.Outcome.EMPTY, 1, 0.0d) == 80L,
                "lowest jitter value must apply the lower bound");
        check(policy.delayMillis(PollBackoffPolicy.Outcome.EMPTY, 1, 0.5d) == 100L,
                "midpoint jitter must preserve the base delay");
        check(policy.delayMillis(PollBackoffPolicy.Outcome.EMPTY, 1, 1.0d) == 120L,
                "highest jitter value must apply the upper bound");
    }

    @Test
    void rejectsInvalidPollInputs() {
        expectFailure(() -> new PollCursor(-1L));
        expectFailure(() -> new PollRequest("consumer", "server", PollCursor.initial(), 0, 0L));
        expectFailure(() -> PollBackoffPolicy.defaults().delayMillis(PollBackoffPolicy.Outcome.EMPTY, 0, 0.5d));
        expectFailure(() -> PollBackoffPolicy.defaults().delayMillis(PollBackoffPolicy.Outcome.EMPTY, 1, 1.01d));
        expectFailure(() -> new TransportEvent(
                "event", "topic", "server-a", "server-b", 100L, 99L, 0L,
                "application/json", new byte[0], Map.of()
        ));
    }

    @Test
    void comparesPayloadBytesByContentForIdempotentPublish() {
        TransportEvent first = event("event", 90L, 100L, new byte[]{1, 2, 3}, Map.of("trace", "one"));
        TransportEvent replay = event("event", 90L, 100L, new byte[]{1, 2, 3}, Map.of("trace", "one"));
        TransportEvent collision = event("event", 90L, 100L, new byte[]{1, 2, 4}, Map.of("trace", "one"));

        check(first.equals(replay), "equivalent payload arrays must compare equal");
        check(first.hashCode() == replay.hashCode(), "equivalent events must share a hash code");
        check(!first.equals(collision), "different payload bytes must remain a collision");
    }

    @Test
    void scopesConsumerCursorByLogicalServer() {
        check("survival-1/cache".equals(DatabasePollingTransport.cursorKey("survival-1", "cache")),
                "cursor key must retain server and consumer identity");
        check(!DatabasePollingTransport.cursorKey("survival-1", "cache").equals(
                        DatabasePollingTransport.cursorKey("survival-2", "cache")),
                "different logical servers must not share a broadcast cursor");
    }

    private static TransportEvent event(
            String eventId,
            long occurredAt,
            long availableAt,
            byte[] payload,
            Map<String, String> headers
    ) {
        return new TransportEvent(
                eventId,
                "economy.balance.changed",
                "survival-1",
                TransportEvent.BROADCAST_TARGET,
                occurredAt,
                availableAt,
                0L,
                "application/json",
                payload,
                headers
        );
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectFailure(Runnable action) {
        expectFailureType(IllegalArgumentException.class, action);
    }

    private static void expectFailureType(Class<? extends RuntimeException> expectedType, Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected " + expectedType.getSimpleName());
        } catch (RuntimeException exception) {
            if (!expectedType.isInstance(exception)) {
                throw exception;
            }
        }
    }
}
