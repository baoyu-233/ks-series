package org.kseries.instanceworld.internal;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SchematicRepository {
    private final Map<String, Path> roots = new ConcurrentHashMap<>();

    public void register(String namespace, Path root) {
        if (namespace == null || !namespace.matches("[A-Za-z0-9_.:-]+")) {
            throw new IllegalArgumentException("Invalid schematic namespace");
        }
        if (root == null) throw new IllegalArgumentException("Schematic root is required");
        roots.put(namespace, root.toAbsolutePath().normalize());
    }

    public void unregister(String namespace) {
        roots.remove(namespace);
    }

    boolean registered(String namespace) {
        return roots.containsKey(namespace);
    }

    public LoadedSchematic load(String namespace, String name) throws Exception {
        Path root = roots.get(namespace);
        if (root == null) throw new IllegalStateException("No schematic root registered for " + namespace);
        Path normalizedRoot = root.toAbsolutePath().normalize();
        String fileName = name.endsWith(".schem") ? name : name + ".schem";
        Path candidate = normalizedRoot.resolve(fileName).normalize();
        if (!candidate.startsWith(normalizedRoot)) throw new IllegalArgumentException("Schematic path escapes its namespace root");
        if (!Files.isRegularFile(candidate) && !name.endsWith(".schem")) {
            candidate = normalizedRoot.resolve(name).normalize();
        }
        if (!candidate.startsWith(normalizedRoot) || !Files.isRegularFile(candidate)) {
            throw new IllegalArgumentException("Schematic not found: " + name);
        }
        ClipboardFormat format = ClipboardFormats.findByFile(candidate.toFile());
        if (format == null) throw new IllegalArgumentException("Unsupported schematic format: " + candidate.getFileName());
        try (InputStream input = Files.newInputStream(candidate); ClipboardReader reader = format.getReader(input)) {
            Clipboard clipboard = reader.read();
            return new LoadedSchematic(candidate, clipboard);
        }
    }

    public record LoadedSchematic(Path source, Clipboard clipboard) { }
}
