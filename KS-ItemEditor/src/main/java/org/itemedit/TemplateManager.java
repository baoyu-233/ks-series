package org.itemedit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 模板管理器 —— 负责模板码的生成、存储、读取、删除。
 *
 * v1.3.0 更新：
 *   - 前缀 pl- (玩家) / op- (管理员) 区分模板类型
 *   - 支持自定义名称（可选）
 *   - 清理旧格式模板
 *   - 导入+预览+修改+导出工作流
 */
public final class TemplateManager {

    private static final String CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RNG = new SecureRandom();

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(ItemData.class, new ItemDataAdapter())
            .create();

    private final JavaPlugin plugin;
    private final Path templateDir;

    public TemplateManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.templateDir = plugin.getDataFolder().toPath().resolve("templates");
        try {
            Files.createDirectories(templateDir);
        } catch (IOException e) {
            plugin.getLogger().warning("无法创建模板目录: " + e.getMessage());
        }
        // ★ 启动时清理旧格式模板
        cleanupOldTemplates();
    }

    /**
     * 模板元数据。
     */
    public static class Template {
        public String code;              // 如 "pl-a1b2c3d4" 或 "op-e5f6g7h8"
        public String name;              // 用户自定义名称（可选）
        public long createdAt;
        public CreatorInfo createdBy;
        public ItemData item;
        public boolean adminTemplate;

        public static class CreatorInfo {
            public String uuid;
            public String name;
        }

        /** 获取展示用标题：自定义名称 > 物品名称 > 材质名 */
        public String displayTitle() {
            if (name != null && !name.isEmpty()) return name;
            if (item != null && item.name != null && !item.name.isEmpty()) return item.name;
            if (item != null && item.material != null) return item.material;
            return "未知物品";
        }

        /** 获取物品材质名 */
        public String itemMaterial() {
            return item != null ? item.material : "未知";
        }
    }

    // ---- 前缀 ----

    private static String prefix(boolean admin) {
        return admin ? "op-" : "pl-";
    }

    // ---- 代码生成 ----

    public String generateCode(boolean admin) {
        String pfx = prefix(admin);
        for (int attempt = 0; attempt < 100; attempt++) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CHARS.charAt(RNG.nextInt(CHARS.length())));
            }
            String fullCode = pfx + sb.toString();
            if (!Files.exists(templateDir.resolve(fullCode + ".json"))) {
                return fullCode;
            }
        }
        return pfx + generateCode(admin).substring(3) + RNG.nextInt(10);
    }

    // ---- CRUD ----

    public Template save(ItemData itemData, UUID creatorUuid, String creatorName,
                         boolean adminTemplate, String customName) {
        String code = generateCode(adminTemplate);
        Template template = new Template();
        template.code = code;
        template.name = (customName != null && !customName.isBlank()) ? customName.trim() : null;
        template.createdAt = System.currentTimeMillis();
        template.createdBy = new Template.CreatorInfo();
        template.createdBy.uuid = creatorUuid.toString();
        template.createdBy.name = creatorName;
        template.item = itemData;
        template.adminTemplate = adminTemplate;

        Path file = templateDir.resolve(code + ".json");
        try (Writer w = new OutputStreamWriter(
                Files.newOutputStream(file), StandardCharsets.UTF_8)) {
            GSON.toJson(template, w);
            plugin.getLogger().info("模板已保存: " + code
                    + (template.name != null ? " \"" + template.name + "\"" : "")
                    + " (作者: " + creatorName
                    + ", " + (adminTemplate ? "管理员" : "玩家") + ")");
        } catch (IOException e) {
            plugin.getLogger().warning("保存模板失败: " + e.getMessage());
        }

        return template;
    }

    /** 旧方法兼容（无自定义名称）。 */
    public Template save(ItemData itemData, UUID creatorUuid, String creatorName,
                         boolean adminTemplate) {
        return save(itemData, creatorUuid, creatorName, adminTemplate, null);
    }

    /**
     * 按码加载模板。支持 pl-/op- 前缀。
     */
    public Template load(String code) {
        if (code == null || !code.matches("^(pl|op)-[a-z0-9]+$")) return null;

        Path file = templateDir.resolve(code + ".json");
        if (!Files.exists(file)) return null;

        try (Reader r = new InputStreamReader(
                Files.newInputStream(file), StandardCharsets.UTF_8)) {
            return GSON.fromJson(r, Template.class);
        } catch (Exception e) {
            plugin.getLogger().warning("加载模板失败 (" + code + "): " + e.getMessage());
            return null;
        }
    }

    /**
     * 按码删除模板。
     */
    public boolean delete(String code) {
        if (code == null || !code.matches("^(pl|op)-[a-z0-9]+$")) return false;
        Path file = templateDir.resolve(code + ".json");
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            plugin.getLogger().warning("删除模板失败 (" + code + "): " + e.getMessage());
            return false;
        }
    }

    /**
     * 列出某玩家的所有模板（按创建时间倒序）。
     */
    public List<Template> listByPlayer(UUID uuid) {
        String uuidStr = uuid.toString();
        List<Template> results = new ArrayList<>();
        try (Stream<Path> files = Files.list(templateDir)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try (Reader r = new InputStreamReader(
                        Files.newInputStream(p), StandardCharsets.UTF_8)) {
                    Template t = GSON.fromJson(r, Template.class);
                    if (t != null && t.createdBy != null
                            && uuidStr.equals(t.createdBy.uuid)) {
                        results.add(t);
                    }
                } catch (Exception ignored) {}
            });
        } catch (IOException e) {
            plugin.getLogger().warning("列出模板失败: " + e.getMessage());
        }
        results.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return results;
    }

    /**
     * 列出所有模板（管理员用）。
     */
    public List<Template> listAll() {
        List<Template> results = new ArrayList<>();
        try (Stream<Path> files = Files.list(templateDir)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try (Reader r = new InputStreamReader(
                        Files.newInputStream(p), StandardCharsets.UTF_8)) {
                    Template t = GSON.fromJson(r, Template.class);
                    if (t != null) results.add(t);
                } catch (Exception ignored) {}
            });
        } catch (IOException e) {
            plugin.getLogger().warning("列出全部模板失败: " + e.getMessage());
        }
        results.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return results;
    }

    /** 模板总数。 */
    public int count() {
        try (Stream<Path> files = Files.list(templateDir)) {
            return (int) files.filter(p -> p.toString().endsWith(".json")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    // ---- 旧模板清理 ----

    /**
     * 删除所有不含 pl-/op- 前缀的旧格式模板文件。
     * 在构造函数中自动调用。
     */
    private void cleanupOldTemplates() {
        try (Stream<Path> files = Files.list(templateDir)) {
            List<Path> oldFiles = files
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        String base = name.replace(".json", "");
                        return !base.matches("^(pl|op)-[a-z0-9]+$");
                    })
                    .collect(Collectors.toList());

            if (!oldFiles.isEmpty()) {
                int count = 0;
                for (Path f : oldFiles) {
                    try {
                        Files.deleteIfExists(f);
                        count++;
                    } catch (IOException ignored) {}
                }
                plugin.getLogger().info("已清理 " + count + " 个旧格式模板（无 pl-/op- 前缀）");
            }
        } catch (IOException e) {
            plugin.getLogger().warning("清理旧模板时出错: " + e.getMessage());
        }
    }

    // ---- 序列化适配器 ----

    private static final class ItemDataAdapter extends TypeAdapter<ItemData> {
        @Override
        public void write(JsonWriter out, ItemData value) throws IOException {
            if (value == null) { out.nullValue(); return; }
            new Gson().toJson(value.toMap(), Map.class, out);
        }

        @Override
        public ItemData read(JsonReader in) throws IOException {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = new Gson().fromJson(in, Map.class);
            if (map == null) return new ItemData();
            return ItemSerializer.fromMap(map);
        }
    }
}
