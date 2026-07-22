package org.kseco.demand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcDemandCampaignStoreTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final StandardItemSignature SCRAP =
            new StandardItemSignature("v1:mmoitem:MATERIAL:KS_FIELD_SCRAP");

    @TempDir
    Path tempDirectory;

    @Test
    void concurrentReservationsCannotExceedTargetOrBudget() throws Exception {
        JdbcDemandCampaignStore store = store("concurrent.db");
        store.initializeSchema();
        store.createCampaign(campaign("frost-surge", 100L, 10_000L, 100L, 100L));

        List<DemandSubmissionResult> results = runConcurrently(20, index -> submission(
                "demand:concurrent:" + index, "frost-surge", UUID.randomUUID(), 10L, 100L, "CASH"), store);

        long accepted = results.stream().mapToLong(DemandSubmissionResult::acceptedQuantity).sum();
        long payout = results.stream().mapToLong(DemandSubmissionResult::payoutMinor).sum();
        DemandCampaignSnapshot snapshot = store.findCampaign("frost-surge").orElseThrow();

        assertEquals(100L, accepted);
        assertEquals(10_000L, payout);
        assertEquals(0L, snapshot.remainingQuantity());
        assertEquals(0L, snapshot.remainingBudgetMinor());
        assertEquals(DemandCampaignStatus.COMPLETED, snapshot.status());
        assertEquals(snapshot.budgetMinor() - snapshot.remainingBudgetMinor(), payout);
    }

    @Test
    void concurrentReplayUsesOneOperationAndOneReservation() throws Exception {
        JdbcDemandCampaignStore store = store("idempotent.db");
        store.initializeSchema();
        store.createCampaign(campaign("idempotent", 100L, 10_000L, 100L, 100L));
        UUID playerId = UUID.randomUUID();
        DemandSubmission request = submission(
                "demand:same-operation", "idempotent", playerId, 10L, 100L, "CASH");

        List<DemandSubmissionResult> results = runConcurrently(12, ignored -> request, store);
        DemandCampaignSnapshot snapshot = store.findCampaign("idempotent").orElseThrow();

        assertTrue(results.stream().allMatch(result -> result.outcome() == DemandSubmissionResult.Outcome.ACCEPTED));
        assertEquals(1L, results.stream().filter(result -> !result.replayed()).count());
        assertEquals(90L, snapshot.remainingQuantity());
        assertEquals(9_000L, snapshot.remainingBudgetMinor());
        assertEquals(10L, store.playerAcceptedQuantity("idempotent", playerId));
    }

    @Test
    void finiteBudgetPartiallyAcceptsThenClosesCampaign() throws Exception {
        JdbcDemandCampaignStore store = store("budget.db");
        store.initializeSchema();
        store.createCampaign(campaign("budget-cap", 100L, 550L, 100L, 100L));

        DemandSubmissionResult result = store.submit(submission(
                "demand:budget", "budget-cap", UUID.randomUUID(), 10L, 100L, "CASH"));
        DemandCampaignSnapshot snapshot = store.findCampaign("budget-cap").orElseThrow();

        assertEquals(DemandSubmissionResult.Code.PARTIALLY_ACCEPTED, result.code());
        assertEquals(5L, result.acceptedQuantity());
        assertEquals(500L, result.payoutMinor());
        assertEquals(95L, snapshot.remainingQuantity());
        assertEquals(50L, snapshot.remainingBudgetMinor());
        assertEquals(DemandCampaignStatus.COMPLETED, snapshot.status());
        assertEquals(snapshot.budgetMinor() - snapshot.remainingBudgetMinor(), result.payoutMinor());
    }

    @Test
    void playerQuotaAndOperationPayloadAreCasBound() throws Exception {
        JdbcDemandCampaignStore store = store("quota.db");
        store.initializeSchema();
        store.createCampaign(campaign("quota", 100L, 10_000L, 100L, 7L));
        UUID playerId = UUID.randomUUID();

        List<DemandSubmissionResult> results = runConcurrently(8, index -> submission(
                "demand:quota:" + index, "quota", playerId, 3L, 100L, "CASH"), store);
        assertEquals(7L, results.stream().mapToLong(DemandSubmissionResult::acceptedQuantity).sum());
        assertEquals(7L, store.playerAcceptedQuantity("quota", playerId));

        DemandSubmissionResult first = store.submit(submission(
                "demand:payload", "quota", UUID.randomUUID(), 1L, 100L, "CASH"));
        DemandSubmissionResult conflict = store.submit(submission(
                "demand:payload", "quota", UUID.randomUUID(), 2L, 100L, "CASH"));
        assertEquals(DemandSubmissionResult.Outcome.ACCEPTED, first.outcome());
        assertEquals(DemandSubmissionResult.Outcome.IDEMPOTENCY_CONFLICT, conflict.outcome());
    }

    @Test
    void nonCashFailsClosedWithoutConsumingCampaignCapacity() throws Exception {
        JdbcDemandCampaignStore store = store("cash-only.db");
        store.initializeSchema();
        store.createCampaign(campaign("cash-only", 10L, 1_000L, 100L, 10L));

        DemandSubmission request = submission(
                "demand:gem", "cash-only", UUID.randomUUID(), 4L, 100L, "GEM");
        DemandSubmissionResult result = store.submit(request);
        DemandSubmissionResult replay = store.submit(request);
        DemandCampaignSnapshot snapshot = store.findCampaign("cash-only").orElseThrow();

        assertEquals(DemandSubmissionResult.Code.UNSUPPORTED_CURRENCY, result.code());
        assertFalse(result.replayed());
        assertTrue(replay.replayed());
        assertEquals(10L, snapshot.remainingQuantity());
        assertEquals(1_000L, snapshot.remainingBudgetMinor());
    }

    @Test
    void statusUsesVersionCasAndDefinitionsCannotBeUnlimited() throws Exception {
        JdbcDemandCampaignStore store = store("status.db");
        store.initializeSchema();
        DemandCampaignDefinition draft = new DemandCampaignDefinition(
                "draft", "frostwaste", SCRAP, 10L, 1_000L, 100L, 10L, "CASH",
                DemandCampaignStatus.DRAFT, NOW - 1_000L, NOW + 60_000L);
        store.createCampaign(draft);

        assertTrue(store.compareAndSetStatus(
                "draft", 0L, DemandCampaignStatus.DRAFT, DemandCampaignStatus.ACTIVE));
        assertFalse(store.compareAndSetStatus(
                "draft", 0L, DemandCampaignStatus.DRAFT, DemandCampaignStatus.ACTIVE));
        assertEquals(1L, store.findCampaign("draft").orElseThrow().version());

        assertThrows(IllegalArgumentException.class,
                () -> campaign("zero-target", 0L, 1_000L, 100L, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> campaign("zero-budget", 10L, 0L, 100L, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> campaign("zero-price", 10L, 1_000L, 0L, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> campaign("zero-quota", 10L, 1_000L, 100L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new DemandCampaignDefinition(
                        "gem", "frostwaste", SCRAP, 10L, 1_000L, 100L, 10L, "GEM",
                        DemandCampaignStatus.ACTIVE, NOW - 1_000L, NOW + 60_000L));
    }

    @Test
    void closedStoreRejectsFurtherJdbcWork() throws Exception {
        JdbcDemandCampaignStore store = store("closed.db");
        store.initializeSchema();
        store.close();

        assertThrows(java.sql.SQLException.class, () -> store.findCampaign("closed"));
    }

    private JdbcDemandCampaignStore store(String fileName) {
        String url = "jdbc:sqlite:" + tempDirectory.resolve(fileName).toAbsolutePath();
        DemandConnectionSupplier supplier = () -> {
            Connection connection = DriverManager.getConnection(url);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=5000");
            }
            return connection;
        };
        return new JdbcDemandCampaignStore(
                supplier, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    private static DemandCampaignDefinition campaign(
            String id,
            long target,
            long budget,
            long price,
            long playerLimit
    ) {
        return new DemandCampaignDefinition(
                id, "frostwaste", SCRAP, target, budget, price, playerLimit, "CASH",
                DemandCampaignStatus.ACTIVE, NOW - 1_000L, NOW + 60_000L);
    }

    private static DemandSubmission submission(
            String operationId,
            String campaignId,
            UUID playerId,
            long quantity,
            long price,
            String currencyId
    ) {
        return new DemandSubmission(
                new DemandOperationId(operationId), campaignId, playerId, SCRAP, quantity, price, currencyId);
    }

    private static List<DemandSubmissionResult> runConcurrently(
            int taskCount,
            java.util.function.IntFunction<DemandSubmission> requestFactory,
            JdbcDemandCampaignStore store
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<DemandSubmissionResult>> tasks = new ArrayList<>();
        for (int index = 0; index < taskCount; index++) {
            int taskIndex = index;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return store.submit(requestFactory.apply(taskIndex));
            });
        }
        try {
            List<Future<DemandSubmissionResult>> futures = tasks.stream().map(executor::submit).toList();
            ready.await();
            start.countDown();
            List<DemandSubmissionResult> results = new ArrayList<>();
            for (Future<DemandSubmissionResult> future : futures) results.add(future.get());
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
