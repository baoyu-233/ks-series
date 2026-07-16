package org.kseries.compat;

import java.util.Map;

public interface CompatModule {
    String id();

    String displayName();

    void enable();

    void reload();

    void disable();

    Map<String, Object> status();
}
