package org.kseco.crossserver.cache;

import java.util.Objects;

/**
 * Stable address of one cache entry shared by multiple server nodes.
 */
public record CacheKey(String namespace, String key) {
    public CacheKey {
        namespace = requireComponent(namespace, "namespace");
        key = requireComponent(key, "key");
    }

    private static String requireComponent(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
