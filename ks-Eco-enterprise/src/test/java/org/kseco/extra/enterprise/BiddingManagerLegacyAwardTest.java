package org.kseco.extra.enterprise;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BiddingManagerLegacyAwardTest {

    @Test
    void legacyAwardAlwaysFailsClosedWithoutRuntimeDependencies() {
        List<String> warnings = new ArrayList<>();
        BiddingManager manager = new BiddingManager(null, null, warnings::add);

        assertNull(manager.awardProject("project-1"));
        assertTrue(warnings.stream().anyMatch(message ->
                message.contains("旧评标入口") && message.contains("托管")));
    }
}
