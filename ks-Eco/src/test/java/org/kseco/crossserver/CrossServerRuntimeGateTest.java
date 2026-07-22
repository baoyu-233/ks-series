package org.kseco.crossserver;

import org.junit.jupiter.api.Test;
import org.kseco.database.DatabaseDialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossServerRuntimeGateTest {
    @Test
    void disabledConfigurationKeepsSingleServerStartupAvailable() {
        CrossServerRuntimeGate.Decision decision = CrossServerRuntimeGate.evaluate(
                false, DatabaseDialect.SQLITE, false);

        assertEquals(CrossServerRuntimeGate.Status.DISABLED, decision.status());
        assertTrue(decision.pluginStartupAllowed());
        assertFalse(decision.runtimeEnabled());
    }

    @Test
    void rejectsLocalDatabaseEvenWhenMutationWiringIsComplete() {
        CrossServerRuntimeGate.Decision decision = CrossServerRuntimeGate.evaluate(
                true, DatabaseDialect.SQLITE, true);

        assertEquals(CrossServerRuntimeGate.Status.REJECTED_DATABASE, decision.status());
        assertFalse(decision.pluginStartupAllowed());
        assertTrue(decision.message().contains("MySQL"));
    }

    @Test
    void rejectsSharedDatabaseUntilEveryMutationPathIsWired() {
        CrossServerRuntimeGate.Decision decision = CrossServerRuntimeGate.evaluate(
                true, DatabaseDialect.POSTGRESQL, false);

        assertEquals(CrossServerRuntimeGate.Status.REJECTED_INCOMPLETE_WIRING, decision.status());
        assertFalse(decision.pluginStartupAllowed());
        assertTrue(decision.message().contains("cluster tasks"));
    }

    @Test
    void allowsRuntimeOnlyWithSharedDatabaseAndCompleteMutationWiring() {
        CrossServerRuntimeGate.Decision decision = CrossServerRuntimeGate.evaluate(
                true, DatabaseDialect.MARIADB, true);

        assertEquals(CrossServerRuntimeGate.Status.READY, decision.status());
        assertTrue(decision.pluginStartupAllowed());
        assertTrue(decision.runtimeEnabled());
    }
}
