package org.kseco.extra.realestate;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class FoliaMetadataContractTest {
    @Test
    void extraDeclaresFoliaSupport() throws Exception {
        Properties metadata = new Properties();
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("META-INF/ks-eco-extra.properties")) {
            assertNotNull(input);
            metadata.load(input);
        }
        assertEquals("true", metadata.getProperty("folia-supported"));
    }
}
