package org.kseries.instanceworld.api;

import java.util.List;

public record InstanceMarker(String tag, List<String> lines, InstancePoint point) {
    public InstanceMarker {
        tag = tag == null ? "" : tag;
        lines = List.copyOf(lines == null ? List.of() : lines);
    }

    public String argument(int lineIndex) {
        return lineIndex >= 0 && lineIndex < lines.size() ? lines.get(lineIndex) : "";
    }
}
