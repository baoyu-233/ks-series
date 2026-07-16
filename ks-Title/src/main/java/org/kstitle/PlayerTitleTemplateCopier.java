package org.kstitle;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Copies one PlayerTitle title definition into ks-Title as a new editable title.
 */
public final class PlayerTitleTemplateCopier {

    private final KsTitle plugin;

    public PlayerTitleTemplateCopier(KsTitle plugin) {
        this.plugin = plugin;
    }

    public CopyResult copyAsNew(int sourceTitleId, String overrideDisplayName) {
        File sourceFile = sourceDbFile();
        if (!sourceFile.exists()) {
            return CopyResult.fail("PlayerTitle.db not found: " + sourceFile.getPath());
        }

        try (Connection src = DriverManager.getConnection("jdbc:sqlite:" + sourceFile.getAbsolutePath())) {
            return copyAsNew(src, sourceTitleId, overrideDisplayName);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to copy PlayerTitle title #" + sourceTitleId, e);
            return CopyResult.fail("copy failed: " + e.getMessage());
        }
    }

    public CopyAllResult copyAllMissing() {
        File sourceFile = sourceDbFile();
        if (!sourceFile.exists()) {
            return new CopyAllResult(false, "PlayerTitle.db not found: " + sourceFile.getPath(), 0, 0, 0, 0);
        }

        int copied = 0, skipped = 0, failed = 0, attributes = 0;
        try (Connection src = DriverManager.getConnection("jdbc:sqlite:" + sourceFile.getAbsolutePath())) {
            for (int sourceTitleId : listSourceTitleIdInts(src)) {
                int existingTargetId = mappedTargetId(sourceTitleId);
                if (existingTargetId > 0 && plugin.titleManager().getDef(existingTargetId) != null) {
                    skipped++;
                    continue;
                }
                if (plugin.titleManager().getDef(sourceTitleId) != null) {
                    rememberMappedTarget(sourceTitleId, sourceTitleId);
                    skipped++;
                    continue;
                }
                CopyResult result = copyAsNew(src, sourceTitleId, "");
                if (result.success()) {
                    copied++;
                    attributes += result.attributeCount();
                } else {
                    failed++;
                }
            }
            return new CopyAllResult(true, "", copied, skipped, failed, attributes);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to copy all PlayerTitle titles", e);
            return new CopyAllResult(false, "copy all failed: " + e.getMessage(), copied, skipped, failed, attributes);
        }
    }

    public List<String> listSourceTitleIds() {
        File sourceFile = sourceDbFile();
        if (!sourceFile.exists()) return List.of();
        try (Connection src = DriverManager.getConnection("jdbc:sqlite:" + sourceFile.getAbsolutePath())) {
            List<String> out = new ArrayList<>();
            out.add("all");
            for (int id : listSourceTitleIdInts(src)) out.add(String.valueOf(id));
            return out;
        } catch (SQLException e) {
            return List.of();
        }
    }

    private CopyResult copyAsNew(Connection src, int sourceTitleId, String overrideDisplayName) throws SQLException {
        SourceTitle source = readSourceTitle(src, sourceTitleId);
        if (source == null) {
            return CopyResult.fail("PlayerTitle title #" + sourceTitleId + " not found");
        }

        String displayName = overrideDisplayName == null || overrideDisplayName.isBlank()
            ? source.displayName()
            : overrideDisplayName;
        displayName = TitleCommand.wrapPlainCommandTitle(displayName);

        int newId = plugin.titleManager().createTitle(displayName, source.description(), "general", "COMMON",
            source.price(), source.unlockType(), source.conditionType(), source.conditionValue());
        if (newId <= 0) {
            return CopyResult.fail("failed to create ks-Title copy");
        }

        plugin.titleManager().updateTitle(newId, displayName, source.description(), "general", "COMMON",
            source.price(), source.unlockType(), source.conditionType(), source.conditionValue(),
            source.visible(), true);

        int attributes = copyAttributes(src, sourceTitleId, newId);
        rememberMappedTarget(sourceTitleId, newId);
        return new CopyResult(true, "", newId, attributes, displayName);
    }

    private List<Integer> listSourceTitleIdInts(Connection src) throws SQLException {
        List<Integer> out = new ArrayList<>();
        try (PreparedStatement ps = src.prepareStatement("SELECT id FROM title_list ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getInt("id"));
        }
        return out;
    }

    private SourceTitle readSourceTitle(Connection src, int sourceTitleId) throws SQLException {
        String permission = "";
        try (PreparedStatement ps = src.prepareStatement(
                "SELECT buy_data FROM title_require WHERE title_id=? AND buy_type='permission' AND buy_data IS NOT NULL LIMIT 1")) {
            ps.setInt(1, sourceTitleId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) permission = rs.getString("buy_data");
            }
        }

        try (PreparedStatement ps = src.prepareStatement(
                "SELECT title_name, buy_type, amount, is_hide, description FROM title_list WHERE id=?")) {
            ps.setInt(1, sourceTitleId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                String buyType = nullToEmpty(rs.getString("buy_type"));
                String unlockType = "ADMIN_GRANT";
                String conditionType = "";
                String conditionValue = "";
                double price = 0;

                if ("permission".equalsIgnoreCase(buyType)) {
                    unlockType = "CONDITION";
                    conditionType = "PERMISSION";
                    conditionValue = permission;
                }

                return new SourceTitle(
                    nullToEmpty(rs.getString("title_name")),
                    nullToEmpty(rs.getString("description")),
                    rs.getInt("is_hide") == 0,
                    price,
                    unlockType,
                    conditionType,
                    conditionValue
                );
            }
        }
    }

    private int copyAttributes(Connection src, int sourceTitleId, int newTitleId) throws SQLException {
        int count = 0;
        Set<String> seen = new HashSet<>();
        try (PreparedStatement ps = src.prepareStatement(
                "SELECT buff_type, buff_content FROM title_buff WHERE title_id=?")) {
            ps.setInt(1, sourceTitleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String buffType = nullToEmpty(rs.getString("buff_type"));
                    String content = rs.getString("buff_content");
                    if (content == null || content.isBlank()) continue;

                    if ("potion_effect".equals(buffType)) {
                        count += copyPotion(newTitleId, content, seen);
                    } else if ("MythicLib".equals(buffType)) {
                        count += copyMythicLib(newTitleId, content, seen);
                    }
                }
            }
        }
        return count;
    }

    private int copyPotion(int newTitleId, String content, Set<String> seen) {
        try {
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();
            if (!json.has("potionName")) return 0;
            String potionName = json.get("potionName").getAsString();
            double level = json.has("potionLevel") ? json.get("potionLevel").getAsDouble() : 1;
            boolean hide = json.has("potionHide") && json.get("potionHide").getAsBoolean();
            String dedupKey = "POTION|" + potionName;
            if (!seen.add(dedupKey)) return 0;
            int attrId = plugin.titleManager().addAttribute(newTitleId, "POTION", potionName, level,
                "{\"hide\":" + hide + "}");
            return attrId > 0 ? 1 : 0;
        } catch (Exception e) {
            plugin.getLogger().warning("Skipped invalid PlayerTitle potion buff while copying title #" + newTitleId
                + ": " + e.getMessage());
            return 0;
        }
    }

    private int copyMythicLib(int newTitleId, String content, Set<String> seen) {
        String[] parts = content.split(":", 2);
        String statKey = parts[0].trim();
        if (statKey.isEmpty()) return 0;
        double amount = parts.length > 1 ? parseDoubleSafe(parts[1].trim()) : 0;
        String dedupKey = "MYTHICLIB_STAT|" + statKey;
        if (!seen.add(dedupKey)) return 0;
        int attrId = plugin.titleManager().addAttribute(newTitleId, "MYTHICLIB_STAT", statKey, amount, "");
        return attrId > 0 ? 1 : 0;
    }

    private File sourceDbFile() {
        File pluginsDir = plugin.getDataFolder().getParentFile();
        if (pluginsDir != null) return new File(pluginsDir, "PlayerTitle/PlayerTitle.db");
        return new File("plugins/PlayerTitle/PlayerTitle.db");
    }

    private int mappedTargetId(int sourceTitleId) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
                "SELECT value FROM ks_title_meta WHERE key=?")) {
            ps.setString(1, mappingKey(sourceTitleId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return -1;
                return Integer.parseInt(rs.getString(1));
            }
        } catch (Exception e) {
            return -1;
        }
    }

    private void rememberMappedTarget(int sourceTitleId, int targetTitleId) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
                "INSERT OR REPLACE INTO ks_title_meta (key, value) VALUES (?,?)")) {
            ps.setString(1, mappingKey(sourceTitleId));
            ps.setString(2, String.valueOf(targetTitleId));
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to remember PlayerTitle copy mapping for #" + sourceTitleId, e);
        }
    }

    private static String mappingKey(int sourceTitleId) {
        return "copyplt.source." + sourceTitleId;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static double parseDoubleSafe(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private record SourceTitle(String displayName, String description, boolean visible, double price,
                               String unlockType, String conditionType, String conditionValue) {}

    public record CopyResult(boolean success, String message, int newTitleId, int attributeCount, String displayName) {
        static CopyResult fail(String message) {
            return new CopyResult(false, message, -1, 0, "");
        }
    }

    public record CopyAllResult(boolean success, String message, int copied, int skipped, int failed, int attributeCount) {}
}
