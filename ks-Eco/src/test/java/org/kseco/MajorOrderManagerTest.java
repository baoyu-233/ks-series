package org.kseco;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class MajorOrderManagerTest {
    @Test
    void keepsRpgProjectMetricDisabledByDefaultAndIgnoresManualProgress() {
        MajorOrderManager manager = new MajorOrderManager(null);

        MajorOrderManager.MetricValue metric = manager.currentValue("season-one", "RPG_PROJECT", 999_999);

        assertFalse(manager.isRpgProjectProgressEnabled());
        assertFalse(metric.available());
        assertEquals(0, metric.value());
        assertEquals("ACTIVE", MajorOrderManager.effectiveStatus("ACTIVE", 10, metric));
        assertEquals(0, MajorOrderManager.progressPercentage(10, metric));
        assertEquals(0, MajorOrderManager.storedManualValue("RPG_PROJECT", 999_999));
    }

    @Test
    void readsExactAbsoluteProgressFromExternalSourceWithoutCappingIt() {
        MajorOrderManager manager = new MajorOrderManager(null);
        manager.setRpgProjectProgressSource(projectId -> {
            assertEquals("season-one", projectId);
            return OptionalDouble.of(125.5);
        });

        MajorOrderManager.MetricValue metric = manager.currentValue("season-one", "rpg_project", 1);

        assertTrue(manager.isRpgProjectProgressEnabled());
        assertTrue(metric.available());
        assertEquals(125.5, metric.value());
        assertEquals("COMPLETED", MajorOrderManager.effectiveStatus("ACTIVE", 100, metric));
        assertEquals(1, MajorOrderManager.progressPercentage(100, metric));
    }

    @Test
    void treatsMissingInvalidOrFailingExternalProgressAsUnavailable() {
        MajorOrderManager manager = new MajorOrderManager(null);

        manager.setRpgProjectProgressSource(projectId -> OptionalDouble.empty());
        assertFalse(manager.currentValue("season-one", "RPG_PROJECT", 10).available());

        for (double invalid : new double[]{-0.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            manager.setRpgProjectProgressSource(projectId -> OptionalDouble.of(invalid));
            assertFalse(manager.currentValue("season-one", "RPG_PROJECT", 10).available());
        }

        manager.setRpgProjectProgressSource(projectId -> {
            throw new IllegalStateException("read model offline");
        });
        assertFalse(manager.currentValue("season-one", "RPG_PROJECT", 10).available());
        assertFalse(manager.currentValue(" ", "RPG_PROJECT", 10).available());
    }

    @Test
    void clearingSourceDisablesRpgProjectMetricAgain() {
        MajorOrderManager manager = new MajorOrderManager(null);
        manager.setRpgProjectProgressSource(projectId -> OptionalDouble.of(4));

        manager.clearRpgProjectProgressSource();

        assertFalse(manager.isRpgProjectProgressEnabled());
        assertFalse(manager.currentValue("season-one", "RPG_PROJECT", 4).available());
    }

    @Test
    void preservesLegacyManualMetricAndSanitizesNumericBoundaries() {
        MajorOrderManager manager = new MajorOrderManager(null);

        MajorOrderManager.MetricValue manual = manager.currentValue("order", "MANUAL", 7.5);

        assertTrue(manual.available());
        assertEquals(7.5, manual.value());
        assertEquals(7.5, MajorOrderManager.storedManualValue("MANUAL", 7.5));
        assertEquals(0, MajorOrderManager.storedManualValue("MANUAL", -1));
        assertEquals(0, MajorOrderManager.storedManualValue("MANUAL", Double.NaN));
        assertEquals(0.0001, MajorOrderManager.normalizeTargetValue(-1));
        assertEquals(1, MajorOrderManager.normalizeTargetValue(Double.POSITIVE_INFINITY));
    }
}
