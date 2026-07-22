package org.kseco;

import org.kseco.database.PortableSqlMutation;
import org.kseco.database.EconomicFeatureSchema;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.sql.*;
import java.util.*;

/**
 * 官方兑换规则管理器。
 * 管理员定义兑换规则（多物品 ↔ 多物品），玩家执行兑换。
 * 官方兑换不收税（纯以物易物）。
 */
public final class ExchangeManager {

    private final KsEco plugin;
    private static final Gson gson = new Gson();
    private static final int MAX_RULE_ITEM_QUANTITY = 2304;

    public ExchangeManager(KsEco plugin) {
        this.plugin = plugin;
        createTable();
        migrateLegacy();
    }

    // ---- 建表 + 迁移 ----

    private void createTable() {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            EconomicFeatureSchema.initializeExchange(conn);
        } catch (SQLException e) {
            plugin.getLogger().warning("创建兑换规则表失败: " + e.getMessage());
        }
    }

    /** 迁移旧单物品数据 → JSON 数组 */
    private void migrateLegacy() {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            // 查所有 inputs_json 为 NULL 的旧规则
            List<OldRule> oldRules = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT id, input_material, input_quantity, input_item_data, " +
                     "output_material, output_quantity, output_item_data " +
                     "FROM ks_eco_exchange_rules WHERE inputs_json IS NULL")) {
                while (rs.next()) {
                    oldRules.add(new OldRule(
                        rs.getString("id"),
                        rs.getString("input_material"),
                        rs.getInt("input_quantity"),
                        rs.getBytes("input_item_data"),
                        rs.getString("output_material"),
                        rs.getInt("output_quantity"),
                        rs.getBytes("output_item_data")
                    ));
                }
            }
            if (oldRules.isEmpty()) return;

            // 迁移每一条
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_eco_exchange_rules SET inputs_json=?, outputs_json=?, name=? WHERE id=?")) {
                for (OldRule r : oldRules) {
                    List<Map<String, Object>> inputs = new ArrayList<>();
                    Map<String, Object> in = new LinkedHashMap<>();
                    in.put("m", r.inputMaterial);
                    in.put("q", r.inputQuantity);
                    in.put("d", r.inputItemData != null ? Base64.getEncoder().encodeToString(r.inputItemData) : null);
                    inputs.add(in);

                    List<Map<String, Object>> outputs = new ArrayList<>();
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("m", r.outputMaterial);
                    out.put("q", r.outputQuantity);
                    out.put("d", r.outputItemData != null ? Base64.getEncoder().encodeToString(r.outputItemData) : null);
                    outputs.add(out);

                    ps.setString(1, gson.toJson(inputs));
                    ps.setString(2, gson.toJson(outputs));
                    ps.setString(3, null); // 旧规则无名称
                    ps.setString(4, r.id);
                    ps.executeUpdate();
                }
            }
            plugin.getLogger().info("已迁移 " + oldRules.size() + " 条旧兑换规则到多物品格式");
        } catch (SQLException e) {
            plugin.getLogger().warning("迁移旧兑换规则失败: " + e.getMessage());
        }
    }

    private record OldRule(String id, String inputMaterial, int inputQuantity, byte[] inputItemData,
                           String outputMaterial, int outputQuantity, byte[] outputItemData) {}

    // ---- CRUD ----

    /**
     * 创建或更新兑换规则。
     * @return 保存后的规则，失败返回 null
     */
    public ExchangeRule upsertRule(String id, String name, List<RuleItem> inputs,
                                    List<RuleItem> outputs, String createdBy) {
        long now = System.currentTimeMillis() / 1000;
        String ruleId = (id != null) ? id : UUID.randomUUID().toString();
        String inputsJson = serializeItems(inputs);
        String outputsJson = serializeItems(outputs);

        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;

            // 同时更新旧列（向后兼容：取第一个 input/output 填入）
            RuleItem firstIn = inputs.isEmpty() ? null : inputs.get(0);
            RuleItem firstOut = outputs.isEmpty() ? null : outputs.get(0);

            String normalizedName = (name != null && !name.isEmpty()) ? name : null;
            PortableSqlMutation.upsert(conn,
                    "UPDATE ks_eco_exchange_rules SET name=?,inputs_json=?,outputs_json=?,input_material=?,"
                            + "input_quantity=?,input_item_data=?,output_material=?,output_quantity=?,"
                            + "output_item_data=?,created_by=? WHERE id=?",
                    ps -> bindRuleUpdate(ps, ruleId, normalizedName, inputsJson, outputsJson,
                            firstIn, firstOut, createdBy),
                    "INSERT INTO ks_eco_exchange_rules (id,name,inputs_json,outputs_json,input_material,"
                            + "input_quantity,input_item_data,output_material,output_quantity,output_item_data,"
                            + "created_by,created_at,enabled) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,1)",
                    ps -> bindRuleInsert(ps, ruleId, normalizedName, inputsJson, outputsJson,
                            firstIn, firstOut, createdBy, now));
            return new ExchangeRule(ruleId, name, inputs, outputs, createdBy, now, true);
        } catch (SQLException e) {
            plugin.getLogger().warning("保存兑换规则失败: " + e.getMessage());
            return null;
        }
    }

    private static void bindRuleUpdate(PreparedStatement ps, String ruleId, String name, String inputsJson,
                                       String outputsJson, RuleItem firstIn, RuleItem firstOut, String createdBy)
            throws SQLException {
        bindRuleValues(ps, 1, name, inputsJson, outputsJson, firstIn, firstOut, createdBy);
        ps.setString(11, ruleId);
    }

    private static void bindRuleInsert(PreparedStatement ps, String ruleId, String name, String inputsJson,
                                       String outputsJson, RuleItem firstIn, RuleItem firstOut, String createdBy,
                                       long now) throws SQLException {
        ps.setString(1, ruleId);
        bindRuleValues(ps, 2, name, inputsJson, outputsJson, firstIn, firstOut, createdBy);
        ps.setLong(12, now);
    }

    private static void bindRuleValues(PreparedStatement ps, int offset, String name, String inputsJson,
                                       String outputsJson, RuleItem firstIn, RuleItem firstOut, String createdBy)
            throws SQLException {
        ps.setString(offset, name);
        ps.setString(offset + 1, inputsJson);
        ps.setString(offset + 2, outputsJson);
        ps.setString(offset + 3, firstIn != null ? firstIn.material() : "AIR");
        ps.setInt(offset + 4, firstIn != null ? firstIn.quantity() : 0);
        ps.setBytes(offset + 5, firstIn != null ? firstIn.itemData() : null);
        ps.setString(offset + 6, firstOut != null ? firstOut.material() : "AIR");
        ps.setInt(offset + 7, firstOut != null ? firstOut.quantity() : 0);
        ps.setBytes(offset + 8, firstOut != null ? firstOut.itemData() : null);
        ps.setString(offset + 9, createdBy);
    }

    /** 删除兑换规则 */
    public boolean deleteRule(String id) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM ks_eco_exchange_rules WHERE id=?")) {
                ps.setString(1, id);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("删除兑换规则失败: " + e.getMessage());
            return false;
        }
    }

    /** 切换启用状态 */
    public boolean toggleRule(String id, boolean enabled) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_eco_exchange_rules SET enabled=? WHERE id=?")) {
                ps.setInt(1, enabled ? 1 : 0);
                ps.setString(2, id);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("切换规则状态失败: " + e.getMessage());
            return false;
        }
    }

    /** 获取单个规则 */
    public ExchangeRule getRule(String id) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM ks_eco_exchange_rules WHERE id=?")) {
                ps.setString(1, id);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    try {
                        return mapRule(rs);
                    } catch (RuntimeException exception) {
                        plugin.getLogger().warning("Rejected invalid exchange rule " + id + ": "
                                + exception.getMessage());
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询兑换规则失败: " + e.getMessage());
        }
        return null;
    }

    public List<ExchangeRule> listEnabledRules() { return listRules(true); }
    public List<ExchangeRule> listAllRules() { return listRules(false); }

    private List<ExchangeRule> listRules(boolean onlyEnabled) {
        List<ExchangeRule> result = new ArrayList<>();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return result;
            String sql = "SELECT * FROM ks_eco_exchange_rules";
            if (onlyEnabled) sql += " WHERE enabled=1";
            sql += " ORDER BY created_at DESC";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    try {
                        result.add(mapRule(rs));
                    } catch (RuntimeException exception) {
                        plugin.getLogger().warning("Skipped invalid exchange rule: " + exception.getMessage());
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询兑换规则失败: " + e.getMessage());
        }
        return result;
    }

    private ExchangeRule mapRule(ResultSet rs) throws SQLException {
        String name = null;
        List<RuleItem> inputs = new ArrayList<>();
        List<RuleItem> outputs = new ArrayList<>();

        // 优先读 JSON 列
        try { name = rs.getString("name"); } catch (SQLException ignored) {}
        try {
            String inJson = rs.getString("inputs_json");
            if (inJson != null && !inJson.isEmpty()) inputs = deserializeItems(inJson);
        } catch (SQLException ignored) {}
        try {
            String outJson = rs.getString("outputs_json");
            if (outJson != null && !outJson.isEmpty()) outputs = deserializeItems(outJson);
        } catch (SQLException ignored) {}

        // 回退到旧列
        if (inputs.isEmpty()) {
            String mat = rs.getString("input_material");
            int qty = rs.getInt("input_quantity");
            byte[] data = rs.getBytes("input_item_data");
            if (mat != null && !mat.equals("AIR") && qty > 0)
                inputs.add(new RuleItem(mat, qty, data));
        }
        if (outputs.isEmpty()) {
            String mat = rs.getString("output_material");
            int qty = rs.getInt("output_quantity");
            byte[] data = rs.getBytes("output_item_data");
            if (mat != null && !mat.equals("AIR") && qty > 0)
                outputs.add(new RuleItem(mat, qty, data));
        }

        return new ExchangeRule(
                rs.getString("id"), name, inputs, outputs,
                rs.getString("created_by"), rs.getLong("created_at"),
                rs.getInt("enabled") == 1);
    }

    // ---- JSON 序列化 ----

    private String serializeItems(List<RuleItem> items) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (RuleItem item : items) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("m", item.material());
            map.put("q", item.quantity());
            if (item.itemData() != null && item.itemData().length > 0)
                map.put("d", Base64.getEncoder().encodeToString(item.itemData()));
            list.add(map);
        }
        return gson.toJson(list);
    }

    @SuppressWarnings("unchecked")
    private List<RuleItem> deserializeItems(String json) {
        List<RuleItem> result = new ArrayList<>();
        List<Map<String, Object>> list = gson.fromJson(json,
                new TypeToken<List<Map<String, Object>>>(){}.getType());
        if (list == null) return result;
        for (Map<String, Object> map : list) {
            String mat = (String) map.get("m");
            double q = map.get("q") instanceof Number n ? n.doubleValue() : 1;
            if (!Double.isFinite(q) || q != Math.rint(q)) {
                throw new IllegalArgumentException("Exchange item quantity must be a finite integer");
            }
            int qty = (int) q;
            String dB64 = (String) map.get("d");
            byte[] data = (dB64 != null && !dB64.isEmpty())
                    ? Base64.getDecoder().decode(dB64) : null;
            result.add(new RuleItem(mat, qty, data));
        }
        return result;
    }

    // ---- 执行兑换 ----

    /**
     * 执行单次兑换：校验所有 input → 扣除 → 给予所有 output。
     * 官方兑换不收税（纯以物易物）。
     * @return null=成功，否则返回错误消息
     */
    public String executeExchange(UUID playerUuid, String playerName, String ruleId) {
        return executeExchangeBatch(playerUuid, playerName, ruleId, 1);
    }

    /**
     * 计算玩家背包条件下，该规则最多能连续兑换多少次（取所有 input 中比例最小的一项）。
     */
    public int computeMaxTimes(org.bukkit.entity.Player player, ExchangeRule rule) {
        if (rule == null || rule.inputs.isEmpty()) return 0;
        int max = Integer.MAX_VALUE;
        for (RuleItem input : rule.inputs) {
            if (input.quantity() <= 0) continue;
            int found = countItems(player, input);
            max = Math.min(max, found / input.quantity());
        }
        return max == Integer.MAX_VALUE ? 0 : max;
    }

    /**
     * 批量执行兑换：一次性按 times 倍校验/扣除/给予，整体原子（任一 input 不足则全部不扣）。
     * 官方兑换不收税（纯以物易物）。
     * @return null=成功，否则返回错误消息
     */
    public String executeExchangeBatch(UUID playerUuid, String playerName, String ruleId, int times) {
        if (times <= 0) return "兑换次数必须大于0";
        // 官方兑换禁令检查
        if (playerUuid != null && plugin.banManager().isBanned(playerUuid, BanManager.BAN_SELL_TO_OFFICIAL)) {
            var detail = plugin.banManager().getBanDetail(playerUuid, BanManager.BAN_SELL_TO_OFFICIAL);
            String reason = detail != null && detail.get("reason") != null ? String.valueOf(detail.get("reason")) : "";
            return "你已被禁止使用官方兑换。" + (reason.isEmpty() ? "" : " 原因: " + reason);
        }
        ExchangeRule rule = getRule(ruleId);
        if (rule == null) return "兑换规则不存在";
        if (!rule.enabled) return "该兑换规则已停用";
        if (rule.inputs.isEmpty()) return "规则配置错误：无输入物品";
        if (rule.outputs.isEmpty()) return "规则配置错误：无输出物品";

        try {
            for (RuleItem input : rule.inputs) totalQuantity(input.quantity(), times);
            for (RuleItem output : rule.outputs) totalQuantity(output.quantity(), times);
        } catch (IllegalArgumentException exception) {
            return "兑换数量过大";
        }

        org.bukkit.entity.Player player = plugin.getServer().getPlayer(playerUuid);
        if (player == null) return "玩家不在线";

        // 1. 检查所有 input 物品是否足够（按 times 倍）
        for (RuleItem input : rule.inputs) {
            Material mat = Material.getMaterial(input.material());
            if (mat == null) return "输入物品类型无效: " + input.material();
            int needed = totalQuantity(input.quantity(), times);
            int found = countItems(player, input);
            if (found < needed) {
                String display = input.toItemStack().hasItemMeta()
                        && input.toItemStack().getItemMeta().hasDisplayName()
                        ? input.toItemStack().getItemMeta().getDisplayName()
                        : mat.name();
                return "背包中「" + display + "」不足。" + (times > 1 ? "兑换 " + times + " 次共" : "") +
                        "需要 " + needed + "，你有 " + found;
            }
        }

        // 2. 扣除所有 input 物品（按 times 倍）
        for (RuleItem input : rule.inputs) {
            int remaining = totalQuantity(input.quantity(), times);
            Material mat = Material.getMaterial(input.material());
            for (ItemStack invItem : player.getInventory().getStorageContents()) {
                if (invItem == null || invItem.getType() != mat) continue;
                if (input.itemData() != null && input.itemData().length > 0) {
                    ItemStack expected = input.toItemStack();
                    if (!invItem.isSimilar(expected)) continue;
                }
                int take = Math.min(remaining, invItem.getAmount());
                invItem.setAmount(invItem.getAmount() - take);
                remaining -= take;
                if (remaining <= 0) break;
            }
        }

        // 3. 给予所有 output 物品（按 times 倍，超出单堆叠上限由 addItem 自动拆分到多个槽位）
        for (RuleItem output : rule.outputs) {
            ItemStack item = output.toItemStack();
            item.setAmount(totalQuantity(output.quantity(), times));
            var leftover = player.getInventory().addItem(item);
            if (!leftover.isEmpty()) {
                for (ItemStack is : leftover.values()) {
                    plugin.storageManager().storeItem(playerUuid, is, "EXCHANGE_OVERFLOW");
                }
                player.sendMessage("§e背包已满，部分物品已放入暂存箱 (/storage)。");
            }
        }

        player.updateInventory();
        return null;
    }

    /** 统计玩家背包中符合 RuleItem 的物品数量 */
    private int countItems(org.bukkit.entity.Player player, RuleItem input) {
        Material mat = Material.getMaterial(input.material());
        if (mat == null) return 0;
        int found = 0;
        for (ItemStack invItem : player.getInventory().getStorageContents()) {
            if (invItem == null || invItem.getType() != mat) continue;
            if (input.itemData() != null && input.itemData().length > 0) {
                ItemStack expected = input.toItemStack();
                if (!invItem.isSimilar(expected)) continue;
            }
            found += invItem.getAmount();
        }
        return found;
    }

    // ---- 数据类 ----

    /** 单个物品定义（input 或 output 的一项） */
    static int totalQuantity(int perExchange, int times) {
        if (perExchange <= 0 || perExchange > MAX_RULE_ITEM_QUANTITY || times <= 0) {
            throw new IllegalArgumentException("Invalid exchange quantity");
        }
        try {
            return Math.multiplyExact(perExchange, times);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Exchange quantity overflow", exception);
        }
    }

    public record RuleItem(String material, int quantity, byte[] itemData) {
        public RuleItem {
            Objects.requireNonNull(material, "material");
            material = material.trim().toUpperCase(Locale.ROOT);
            if (material.isEmpty() || Material.getMaterial(material) == null) {
                throw new IllegalArgumentException("Invalid exchange material: " + material);
            }
            if (quantity <= 0 || quantity > MAX_RULE_ITEM_QUANTITY) {
                throw new IllegalArgumentException("Exchange item quantity must be between 1 and "
                        + MAX_RULE_ITEM_QUANTITY);
            }
            itemData = itemData == null ? null : itemData.clone();
        }

        @Override
        public byte[] itemData() {
            return itemData == null ? null : itemData.clone();
        }

        public ItemStack toItemStack() {
            if (itemData != null && itemData.length > 0)
                return ItemStack.deserializeBytes(itemData);
            Material m = Material.getMaterial(material);
            return m != null ? new ItemStack(m) : new ItemStack(Material.BARRIER);
        }

        /** 简短描述，用于 GUI lore */
        public String display() {
            ItemStack is = toItemStack();
            if (is.hasItemMeta() && is.getItemMeta().hasDisplayName())
                return is.getItemMeta().getDisplayName();
            return material;
        }
    }

    /** 兑换规则 */
    public record ExchangeRule(
            String id,
            String name,
            List<RuleItem> inputs,
            List<RuleItem> outputs,
            String createdBy,
            long createdAt,
            boolean enabled
    ) {
        /** 显示用名称，无名则用 ID 前 8 位 */
        public String displayName() {
            return (name != null && !name.isEmpty()) ? name : "规则#" + id.substring(0, 8);
        }

        /** 输入物品摘要（用于 player 模式 lore） */
        public String inputSummary() {
            StringBuilder sb = new StringBuilder();
            for (RuleItem item : inputs) {
                if (!sb.isEmpty()) sb.append(" §7+ ");
                sb.append(item.quantity()).append("x ").append(item.display());
            }
            return sb.toString();
        }

        /** 输出物品摘要 */
        public String outputSummary() {
            StringBuilder sb = new StringBuilder();
            for (RuleItem item : outputs) {
                if (!sb.isEmpty()) sb.append(" §7+ ");
                sb.append(item.quantity()).append("x ").append(item.display());
            }
            return sb.toString();
        }

        /** 取第一个输出物品做 GUI 图标 */
        public ItemStack iconItem() {
            return outputs.isEmpty() ? new ItemStack(Material.BARRIER) : outputs.get(0).toItemStack();
        }
    }
}
