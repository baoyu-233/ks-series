package org.kseco;

import org.bukkit.command.CommandSender;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 经济数据重置 — 测试期结束、正式开服前按需清空玩家产生的测试数据，保留管理员手动配置的规则。
 * 支持按类别选择清空范围，且每次清空前都会自动全量备份，可随时回档恢复。
 */
public final class EconomyResetManager {

    /** 可选清空类别。每个类别对应一组表，互相独立，管理员按需勾选。 */
    public enum Category {
        CORE("经济核心", "余额 / 市场挂单 / 交易记录 / 暂存箱",
                  List.of("ks_builtin_economy", "ks_eco_trades", "ks_eco_transport_log", "ks_eco_storage", "ks_eco_listings")),
        BANK("银行+企业+招投标", "银行账户/贷款/成员、企业、招投标、保证金（ks_bank_banks 清空后会自动重建 CORP-BANK）",
                List.of("ks_bank_banks", "ks_bank_accounts", "ks_bank_loans", "ks_bank_loan_requests",
                        "ks_bank_cb_loans", "ks_bank_members", "ks_bank_permissions", "ks_bank_money_supply",
                        "ks_ent_enterprises", "ks_ent_members", "ks_ent_permissions", "ks_ent_invites",
                        "ks_ent_corporate_accounts", "ks_ent_dividends", "ks_ent_projects", "ks_ent_bids",
                        "ks_ent_bid_deposits", "ks_ent_procurements", "ks_ent_procurement_bids")),
        TAX("税收记录", "税收流水/罚单（不含税率档位配置）",
                List.of("ks_tax_records", "ks_tax_penalties")),
        REALESTATE("房地产", "地块/商品房/信任名单/分区规划",
                List.of("ks_re_plots", "ks_re_houses", "ks_re_plot_trust", "ks_re_house_trust", "ks_re_zones")),
        DUNGEON("副本", "实例进度/参与者/网格分配（不含地图模板）",
                List.of("ks_dungeon_instances", "ks_dungeon_participants", "ks_dungeon_revivals",
                        "ks_dungeon_log", "ks_dungeon_grids")),
        POLITIC("政治", "职务/提案/投票记录（不含席位数等配置）",
                List.of("ks_politic_offices", "ks_politic_proposals", "ks_politic_votes", "ks_politic_election_votes")),
        BLINDBOX("盲盒", "抽奖记录/保底计数（不含奖池与掉落表配置）",
                List.of("ks_bb_pity", "ks_bb_log")),
        AUDIT("审计日志", "管理与经济操作审计记录",
                List.of("ks_audit_log"));

        public final String label;
        public final String description;
        public final List<String> tables;

        Category(String label, String description, List<String> tables) {
            this.label = label;
            this.description = description;
            this.tables = tables;
        }

        public static Category byKey(String key) {
            for (Category c : values()) {
                if (c.name().equalsIgnoreCase(key)) return c;
            }
            return null;
        }
    }

    private static final String CORP_BANK_ID = "CORP-BANK";
    private static final String BACKUP_PREFIX = "data.db.bak_pre_economyreset_";
    private static final String ROLLBACK_BACKUP_PREFIX = "data.db.bak_pre_rollback_";

    private final KsEco plugin;

    public EconomyResetManager(KsEco plugin) {
        this.plugin = plugin;
    }

    // ---- 预览 ----

    /** 预览：统计所选类别涉及的每张表当前行数，不做任何修改 */
    public Map<String, Integer> preview(Set<Category> categories) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return counts;
            for (Category cat : categories) {
                for (String table : cat.tables) {
                    try (Statement st = conn.createStatement();
                         ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        counts.put(table, rs.next() ? rs.getInt(1) : 0);
                    } catch (SQLException ignored) {
                        // 表不存在（对应扩展模块未安装），跳过
                    }
                }
            }
        } catch (SQLException ignored) {
            // 关闭连接失败，忽略
        }
        return counts;
    }

    // ---- 清空 ----

    /**
     * 执行清空：先在线备份整库（SQLite VACUUM INTO），再清空所选类别涉及的表，
     * 若包含 BANK 类别则重建必需的种子数据（企业商业银行），使其无需重启即可继续使用。
     * @return 每张表实际删除的行数；备份失败时返回空 Map 且不做任何删除
     */
    public synchronized Map<String, Integer> reset(CommandSender sender, Set<Category> categories) {
        Map<String, Integer> deleted = new LinkedHashMap<>();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) {
                sender.sendMessage("§c数据库未连接，重置已中止。");
                return deleted;
            }

            String backupPath = backupDatabase(conn, BACKUP_PREFIX);
            if (backupPath == null) {
                sender.sendMessage("§c备份失败，已中止重置（未删除任何数据）。");
                return deleted;
            }
            sender.sendMessage("§a已备份完整数据库到: " + backupPath);

            Set<String> tables = new LinkedHashSet<>();
            for (Category cat : categories) tables.addAll(cat.tables);

            for (String table : tables) {
                try (Statement st = conn.createStatement()) {
                    int n = st.executeUpdate("DELETE FROM " + table);
                    deleted.put(table, n);
                } catch (SQLException e) {
                    // 表不存在，跳过
                }
            }

            if (categories.contains(Category.BANK)) reseedBankDefaults(conn);

            plugin.getLogger().warning("[ks-Eco] 经济数据已被 " + sender.getName() + " 重置（类别: " +
                    categories.stream().map(Enum::name).reduce((a, b) -> a + "," + b).orElse("") +
                    "）。备份: " + backupPath);
        } catch (SQLException e) {
            sender.sendMessage("§c重置过程中出错: " + e.getMessage());
        }
        return deleted;
    }

    // ---- 备份管理 / 回档 ----

    /** 列出所有可回档的备份文件（按时间倒序） */
    public List<File> listBackups() {
        File dataFolder = plugin.ksCore().getDataFolder();
        File[] files = dataFolder.listFiles((dir, name) ->
                name.startsWith(BACKUP_PREFIX) || name.startsWith(ROLLBACK_BACKUP_PREFIX));
        List<File> list = new ArrayList<>();
        if (files != null) list.addAll(List.of(files));
        list.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return list;
    }

    /**
     * 从指定备份文件回档：先把当前数据库状态再备份一份做安全网，
     * 然后通过 ATTACH 把备份库中存在的每张表整体覆盖回主库（不重启即可生效）。
     * @param backupFileName 必须是 listBackups() 返回的文件名之一（防止路径穿越）
     * @return 实际还原的表名列表；失败返回 null
     */
    public synchronized List<String> rollback(CommandSender sender, String backupFileName) {
        File dataFolder = plugin.ksCore().getDataFolder();
        File backupFile = new File(dataFolder, backupFileName);
        boolean valid = listBackups().stream().anyMatch(f -> f.getName().equals(backupFileName));
        if (!valid || !backupFile.isFile()) {
            sender.sendMessage("§c备份文件不存在或不合法: " + backupFileName);
            return null;
        }

        List<String> restored = new ArrayList<>();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) {
                sender.sendMessage("§c数据库未连接，回档已中止。");
                return null;
            }

            // 回档前先把当前状态也备份一份，防止回档本身需要撤销
            String safetyBackup = backupDatabase(conn, ROLLBACK_BACKUP_PREFIX);
            if (safetyBackup == null) {
                sender.sendMessage("§c回档前的安全备份失败，已中止回档。");
                return null;
            }
            sender.sendMessage("§a回档前已备份当前状态到: " + safetyBackup);

            String backupPath = backupFile.getAbsolutePath().replace("'", "''");
            try (Statement st = conn.createStatement()) {
                st.execute("ATTACH DATABASE '" + backupPath + "' AS backup_src");
                try {
                    List<String> tables = new ArrayList<>();
                    try (ResultSet rs = st.executeQuery(
                            "SELECT name FROM backup_src.sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
                        while (rs.next()) tables.add(rs.getString("name"));
                    }
                    for (String table : tables) {
                        try {
                            st.executeUpdate("DELETE FROM main." + table);
                            st.executeUpdate("INSERT INTO main." + table + " SELECT * FROM backup_src." + table);
                            restored.add(table);
                        } catch (SQLException e) {
                            // 主库里没有这张表，或表结构已变更导致列不匹配（比如备份是改表结构之前生成的），跳过
                            plugin.getLogger().warning("[ks-Eco] 回档表 " + table + " 失败，已跳过: " + e.getMessage());
                        }
                    }
                } finally {
                    st.execute("DETACH DATABASE backup_src");
                }
            }

            plugin.getLogger().warning("[ks-Eco] 经济数据库已被 " + sender.getName() + " 从备份 " + backupFileName + " 回档，共还原 " + restored.size() + " 张表。");
        } catch (SQLException e) {
            sender.sendMessage("§c回档失败: " + e.getMessage());
            return null;
        }
        return restored;
    }

    /** 在给定连接上执行在线全量备份（VACUUM INTO），不开新连接，避免连接泄漏 */
    private String backupDatabase(Connection conn, String prefix) {
        try {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            File backupFile = new File(plugin.ksCore().getDataFolder(), prefix + ts);
            String path = backupFile.getAbsolutePath().replace("'", "''");
            try (Statement st = conn.createStatement()) {
                st.execute("VACUUM INTO '" + path + "'");
            }
            return backupFile.getAbsolutePath();
        } catch (SQLException e) {
            plugin.getLogger().severe("[ks-Eco] 备份失败: " + e.getMessage());
            return null;
        }
    }

    /** 重新种入清空银行表后仍需立即可用的默认行（无需重启） */
    private void reseedBankDefaults(Connection conn) {
        try (Statement st = conn.createStatement()) {
            long now = System.currentTimeMillis() / 1000;
            st.executeUpdate("INSERT OR IGNORE INTO ks_bank_banks (id, name, type, owner_uuids, total_assets, created_at) VALUES ('" +
                    CORP_BANK_ID + "', '企业商业银行', 'COMMERCIAL', 'SYSTEM', 1000000000, " + now + ")");
            st.executeUpdate("INSERT OR IGNORE INTO ks_bank_members (bank_id, player_uuid, player_name, role) VALUES ('" +
                    CORP_BANK_ID + "', 'SYSTEM', '官方', 'OWNER')");
        } catch (SQLException e) {
            plugin.getLogger().warning("[ks-Eco] 重置后重建默认数据失败: " + e.getMessage());
        }
    }
}
