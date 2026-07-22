package org.kseries.botguard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KsBotGuardListenerLifecycleTest {

    @Test
    void restoresOnlyWhenTheTrackedWrapperIsStillLive() {
        assertTrue(KsBotGuard.shouldRestoreOriginal(true, true, false));
        assertFalse(KsBotGuard.shouldRestoreOriginal(false, true, false));
    }

    @Test
    void neverRestoresDisabledOrAlreadyRegisteredListeners() {
        assertFalse(KsBotGuard.shouldRestoreOriginal(true, false, false));
        assertFalse(KsBotGuard.shouldRestoreOriginal(true, true, true));
    }
}
