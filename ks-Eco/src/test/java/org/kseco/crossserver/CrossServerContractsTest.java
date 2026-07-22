package org.kseco.crossserver;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** Contract checks for the cross-server value objects. */
public final class CrossServerContractsTest {
    @Test
    void createsImmutableEventEnvelope() {
        Instant now = Instant.parse("2026-07-18T10:00:00Z");
        ServerInstance source = new ServerInstance("survival-1", "instance-1");
        CrossServerEventEnvelope event = CrossServerEventEnvelope.create(
                "economy.currency",
                "balance.changed",
                UUID.randomUUID().toString(),
                "{\"currency\":\"gold_bar\",\"delta\":1}",
                source,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        check(event.sourceServerId().equals("survival-1"), "source server must be retained");
        check(event.sourceInstanceId().equals("instance-1"), "source instance must be retained");
        check(event.occurredAt().equals(now), "clock snapshot must be retained");
        check(event.schemaVersion() == CrossServerEventEnvelope.CURRENT_SCHEMA_VERSION, "schema version mismatch");
    }

    @Test
    void separatesLogicalServerFromProcessInstance() {
        ServerInstance first = new ServerInstance("lobby", "boot-a");
        ServerInstance second = new ServerInstance("lobby", "boot-b");
        check(first.serverId().equals(second.serverId()), "logical server IDs should match");
        check(!first.ownerId().equals(second.ownerId()), "process owner IDs must differ across restarts");
    }

    @Test
    void validatesHeartbeatWindow() {
        Instant started = Instant.parse("2026-07-18T10:00:00Z");
        ServerHeartbeat heartbeat = new ServerHeartbeat(
                "survival-1",
                "instance-1",
                started,
                started.plusSeconds(5),
                started.plusSeconds(20),
                "{}"
        );
        check(heartbeat.isAliveAt(started.plusSeconds(10)), "heartbeat should be alive inside its window");
        check(!heartbeat.isAliveAt(started.plusSeconds(20)), "heartbeat expires at the exclusive boundary");
        expectFailure(() -> new ServerHeartbeat(
                "survival-1",
                "instance-1",
                started,
                started.minusSeconds(1),
                started.plusSeconds(20),
                "{}"
        ));
    }

    @Test
    void representsIdempotentInboxOutcomes() {
        UUID eventId = UUID.randomUUID();
        InboxClaim claimed = InboxClaim.claimed(
                eventId,
                "balance-cache",
                "survival-1/instance-1",
                Instant.parse("2026-07-18T10:00:30Z"),
                1
        );
        InboxClaim duplicate = InboxClaim.alreadyProcessed(eventId, "balance-cache", 1);
        check(claimed.status() == InboxClaim.Status.CLAIMED, "new inbox key should be claimable");
        check(duplicate.status() == InboxClaim.Status.ALREADY_PROCESSED, "processed key should deduplicate");
        expectFailure(() -> new OutboxClaim(
                sampleEvent(),
                "survival-1/instance-1",
                Instant.now(),
                0
        ));
    }

    @Test
    void enforcesLeaseFencingToken() {
        Instant now = Instant.parse("2026-07-18T10:00:00Z");
        LeaseRecord lease = new LeaseRecord(
                "official-market-sweep",
                "survival-1/instance-1",
                7,
                now.plus(Duration.ofSeconds(15)),
                now
        );
        check(lease.isHeldBy("survival-1/instance-1", now.plusSeconds(10)), "current owner should hold lease");
        check(!lease.isHeldBy("survival-1/old-instance", now.plusSeconds(10)), "stale owner must be fenced");
        expectFailure(() -> new LeaseRecord(
                "official-market-sweep",
                "survival-1/instance-1",
                0,
                now.plusSeconds(15),
                now
        ));
    }

    private static CrossServerEventEnvelope sampleEvent() {
        return new CrossServerEventEnvelope(
                UUID.randomUUID(),
                "economy.currency",
                "balance.changed",
                "player-1",
                "survival-1",
                "instance-1",
                "{}",
                1,
                Instant.now()
        );
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected contract rejection.
        }
    }
}
