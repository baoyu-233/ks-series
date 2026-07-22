package org.kseco.extra.enterprise;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FoliaMetadataContractTest {
    @Test
    void declaresFoliaCapabilityAndEntryPoint() throws Exception {
        Properties metadata = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/META-INF/ks-eco-extra.properties")) {
            assertNotNull(input);
            metadata.load(input);
        }
        assertEquals("org.kseco.extra.enterprise.EnterpriseExtra", metadata.getProperty("main-class"));
        assertEquals("true", metadata.getProperty("folia-supported"));
    }
}
