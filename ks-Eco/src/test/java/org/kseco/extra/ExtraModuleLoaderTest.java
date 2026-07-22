package org.kseco.extra;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtraModuleLoaderTest {
    @Test
    void paperAcceptsLegacyDescriptor() {
        assertTrue(ExtraModuleLoader.supportsRuntime(new Properties(), false));
    }

    @Test
    void foliaRejectsMissingOrFalseCapability() {
        Properties missing = new Properties();
        Properties disabled = new Properties();
        disabled.setProperty("folia-supported", "false");

        assertFalse(ExtraModuleLoader.supportsRuntime(missing, true));
        assertFalse(ExtraModuleLoader.supportsRuntime(disabled, true));
    }

    @Test
    void foliaAcceptsExplicitCapability() {
        Properties supported = new Properties();
        supported.setProperty("folia-supported", "true");

        assertTrue(ExtraModuleLoader.supportsRuntime(supported, true));
    }
}
