package org.kseco.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.kseco.KsEco;

import java.util.*;

/**
 * 政治 GUI — 身份/职务、保民官选举、提案列表、投票。
 * 全部通过反射访问 ks-Eco-politic 模块。
 */
public final class PoliticGui implements InventoryHolder {

    private final KsEco plugin;
    private Inventory inventory;
    private int view = 0; // 0=identity, 1=election, 2=proposals
    private int page = 0;
    private final List<Map<String, Object>> items = new ArrayList<>();
    private static final int ROWS = 6;
    // Bottom row is reserved for paging, proposal creation and navigation controls.
    private static final int PAGE_SIZE = 36;
    private static final Set<String> SENATE_VOTE_STAGES = Set.of("SENATE_VOTING", "SENATE_OVERRIDE");
    private static final Set<String> APPEAL_STAGES = Set.of("SENATE_VOTING", "SENATE_OVERRIDE", "TRIBUNE_REVIEW");

    // Reflection getter helpers
    private Object getPoliticManager() {
        var mod = plugin.extraModuleLoader().getModule("ks-eco-politic");
        if (mod == null) return null;
        try {
            var m = mod.getClass().getMethod("politicManager");
            return m.invoke(mod);
        } catch (Exception e) { return null; }
    }

    private Object getProposalManager() {
        var mod = plugin.extraModuleLoader().getModule("ks-eco-politic");
        if (mod == null) return null;
        try {
            var m = mod.getClass().getMethod("proposalManager");
            return m.invoke(mod);
        } catch (Exception e) { return null; }
    }

    private Object getVoteManager() {
        var mod = plugin.extraModuleLoader().getModule("ks-eco-politic");
        if (mod == null) return null;
        try {
            var m = mod.getClass().getMethod("voteManager");
            return m.invoke(mod);
        } catch (Exception e) { return null; }
    }

    public PoliticGui(KsEco plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        this.page = 0;
        this.view = 0;
        loadData(player);
        build();
        player.openInventory(inventory);
    }

    @SuppressWarnings("unchecked")
    private void loadData(Player player) {
        items.clear();
        refreshViewerCapabilities(player);
        if (plugin.extraModuleLoader().getModule("ks-eco-politic") == null) return;

        try {
            switch (view) {
                case 0 -> {
                    // 身份信息
                    Object pm = getPoliticManager();
                    if (pm == null) break;
                    String office = (String) pm.getClass().getMethod("getPlayerOffice", UUID.class)
                            .invoke(pm, player.getUniqueId());
                    boolean isSenator = (boolean) pm.getClass().getMethod("isSenator", UUID.class)
                            .invoke(pm, player.getUniqueId());
                    boolean isConsul = (boolean) pm.getClass().getMethod("isConsul", UUID.class)
                            .invoke(pm, player.getUniqueId());
                    boolean isTribune = (boolean) pm.getClass().getMethod("isTribune", UUID.class)
                            .invoke(pm, player.getUniqueId());
                    boolean isEquestrian = (boolean) pm.getClass().getMethod("isEquestrian", UUID.class)
                            .invoke(pm, player.getUniqueId());
                    boolean canPropose = (boolean) pm.getClass().getMethod("canPropose", UUID.class)
                            .invoke(pm, player.getUniqueId());
                    boolean canVote = (boolean) pm.getClass().getMethod("canVoteInSenate", UUID.class)
                            .invoke(pm, player.getUniqueId());

                    Map<String, Object> identity = new LinkedHashMap<>();
                    identity.put("office", office != null ? office : "平民");
                    identity.put("isSenator", isSenator);
                    identity.put("isConsul", isConsul);
                    identity.put("isTribune", isTribune);
                    identity.put("isEquestrian", isEquestrian);
                    identity.put("canPropose", canPropose);
                    identity.put("canVote", canVote);
                    items.add(identity);

                    // Senate composition
                    var senators = (List<?>) pm.getClass().getMethod("getSenators").invoke(pm);
                    var consul = pm.getClass().getMethod("getConsul").invoke(pm);
                    var tribunes = (List<?>) pm.getClass().getMethod("getTribunes").invoke(pm);
                    var equestrians = (List<?>) pm.getClass().getMethod("getEquestrians").invoke(pm);

                    Map<String, Object> comp = new LinkedHashMap<>();
                    comp.put("type", "composition");
                    comp.put("senators", senators != null ? senators.size() : 0);
                    comp.put("consul", consul != null ? 1 : 0);
                    comp.put("tribunes", tribunes != null ? tribunes.size() : 0);
                    comp.put("equestrians", equestrians != null ? equestrians.size() : 0);
                    items.add(comp);
                }
                case 1 -> {
                    // 保民官选举
                    Object pm = getPoliticManager();
                    if (pm == null) break;
                    String electionId = (String) pm.getClass().getMethod("getTribuneElectionId").invoke(pm);
                    long endsAt = ((Number) pm.getClass().getMethod("getTribuneElectionEndsAt").invoke(pm)).longValue();
                    String myVote = (String) pm.getClass().getMethod("getMyTribuneVote", String.class, UUID.class)
                            .invoke(pm, electionId, player.getUniqueId());
                    var tally = (List<Map<String, Object>>) pm.getClass()
                            .getMethod("getTribuneElectionTally", String.class).invoke(pm, electionId);

                    Map<String, Map<String, Object>> candidates = new LinkedHashMap<>();
                    if (tally != null) {
                        for (var t : tally) {
                            String candidateUuid = nullableString(t.get("candidateUuid"));
                            if (candidateUuid == null) continue;
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("type", "candidate");
                            // PoliticManager.getTribuneElectionTally 返回 camelCase key
                            row.put("candidate_uuid", candidateUuid);
                            row.put("candidate_name", t.get("candidateName"));
                            row.put("votes", t.get("votes"));
                            row.put("my_vote", myVote);
                            row.put("election_id", electionId);
                            candidates.put(candidateUuid, row);
                        }
                    }

                    // The election has no registration table: every eligible player is a candidate.
                    // Tally only contains players who already have votes, so add eligible online players
                    // to make the first vote possible from the GUI as well.
                    if (electionId != null && !electionId.isBlank()) {
                        var eligibleMethod = pm.getClass().getMethod("isTribuneCandidateEligible", UUID.class);
                        for (Player candidate : Bukkit.getOnlinePlayers()) {
                            if (!(boolean) eligibleMethod.invoke(pm, candidate.getUniqueId())) continue;
                            String candidateUuid = candidate.getUniqueId().toString();
                            candidates.computeIfAbsent(candidateUuid, ignored -> {
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("type", "candidate");
                                row.put("candidate_uuid", candidateUuid);
                                row.put("candidate_name", candidate.getName());
                                row.put("votes", 0);
                                row.put("my_vote", myVote);
                                row.put("election_id", electionId);
                                return row;
                            });
                        }
                    }
                    items.addAll(candidates.values());

                    if (electionId != null && !electionId.isBlank() && endsAt > 0) {
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("type", "election_info");
                        info.put("ends_at", endsAt);
                        info.put("election_id", electionId);
                        info.put("my_vote", myVote);
                        items.add(0, info);
                    }
                }
                case 2 -> {
                    // 提案列表 - listProposals 返回 List<Proposal>，每项需 toMap()
                    Object propMgr = getProposalManager();
                    if (propMgr == null) break;
                    var proposals = (List<?>) propMgr.getClass()
                            .getMethod("listProposals", String.class, String.class, int.class)
                            .invoke(propMgr, null, null, 200);
                    Object voteMgr = getVoteManager();
                    if (proposals != null) {
                        for (Object p : proposals) {
                            try {
                                Map<String, Object> m = (Map<String, Object>) p.getClass()
                                        .getMethod("toMap").invoke(p);
                                String status = String.valueOf(m.getOrDefault("status", ""));
                                String proposalId = nullableString(m.get("id"));
                                if (voteMgr != null && proposalId != null && APPEAL_STAGES.contains(status)) {
                                    try {
                                        String tallyStage = "TRIBUNE_REVIEW".equals(status)
                                                ? "SENATE_VOTING" : status;
                                        Object tally = voteMgr.getClass().getMethod("countVotes", String.class, String.class)
                                                .invoke(voteMgr, proposalId, tallyStage);
                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> tallyMap = (Map<String, Object>) tally.getClass()
                                                .getMethod("toMap").invoke(tally);
                                        m.put("voteSummary", tallyMap.get("summary"));
                                        m.put("totalVoted", tallyMap.get("totalVoted"));
                                        m.put("totalEligible", tallyMap.get("totalEligible"));
                                    } catch (Exception enrichmentError) {
                                        plugin.getLogger().warning("PoliticGui 读取提案计票失败 " + proposalId
                                                + ": " + enrichmentError.getMessage());
                                    }
                                }
                                items.add(m);
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("PoliticGui 加载失败: " + e.getMessage());
        }
    }

    private void build() {
        String[] viewNames = {"政治身份", "保民官选举", "提案列表"};
        inventory = Bukkit.createInventory(this, ROWS * 9,
                Component.text("§8元老院 — " + viewNames[view] + " 第" + (page + 1) + "页"));

        // View tabs
        Material[] tabIcons = {Material.BOOK, Material.PAPER, Material.WRITABLE_BOOK};
        String[] tabLabels = {"§5身份", "§e选举", "§a提案"};
        for (int v = 0; v < 3; v++) {
            String label = tabLabels[v] + (v == view ? " §l◀" : "");
            inventory.setItem(v, navButton(tabIcons[v], label, v == view ? "§7（当前）" : "§7点击切换"));
        }

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && (start + i) < items.size(); i++) {
            inventory.setItem(9 + i, buildItem(items.get(start + i)));
        }
        if (items.isEmpty()) inventory.setItem(22, emptyHint());

        if (page > 0)
            inventory.setItem(45, navButton(Material.ARROW, "§a◀ 上一页"));
        inventory.setItem(49, navButton(Material.OAK_DOOR, "§c✕ 返回主菜单", "§7回到经济面板"));
        if ((page + 1) * PAGE_SIZE < items.size())
            inventory.setItem(53, navButton(Material.ARROW, "§a▶ 下一页"));

        // 发起提案（执政官/骑士等有提案权者可见；服务端 createProposal 会再次校验）
        if (view == 2 && viewerCanPropose) {
            inventory.setItem(47, navButton(Material.WRITABLE_BOOK, "§6📜 发起提案",
                    "§7支持: 普通决议 / 设置税率 / 央行利率",
                    "§7复杂类型（阶梯税率/官方定价/区域规划）请用网页端",
                    "§7点击后按聊天提示逐步填写"));
        }

        fillEmpty();
    }

    private boolean viewerCanPropose = false;
    private boolean viewerCanVoteInSenate = false;
    private boolean viewerCanVeto = false;
    private boolean viewerCanAppealPermission = false;
    private UUID viewerUuid;

    private void refreshViewerCapabilities(Player player) {
        viewerCanPropose = false;
        viewerCanVoteInSenate = false;
        viewerCanVeto = false;
        viewerCanAppealPermission = player.hasPermission("kseco.politic.appeal")
                || player.hasPermission("kseco.admin");
        viewerUuid = player.getUniqueId();
        Object pm = getPoliticManager();
        if (pm == null) return;
        try {
            viewerCanPropose = (boolean) pm.getClass().getMethod("canPropose", UUID.class)
                    .invoke(pm, player.getUniqueId());
            viewerCanVoteInSenate = (boolean) pm.getClass().getMethod("canVoteInSenate", UUID.class)
                    .invoke(pm, player.getUniqueId());
            viewerCanVeto = (boolean) pm.getClass().getMethod("canVeto", UUID.class)
                    .invoke(pm, player.getUniqueId());
        } catch (Exception ignored) {}
    }

    private boolean canViewerAppeal(String status, String proposerUuid) {
        if (!viewerCanAppealPermission || viewerUuid == null || !APPEAL_STAGES.contains(status)) return false;
        if (proposerUuid != null && viewerUuid.toString().equals(proposerUuid)) return true;
        return switch (status) {
            case "SENATE_VOTING", "SENATE_OVERRIDE" -> viewerCanVoteInSenate;
            case "TRIBUNE_REVIEW" -> viewerCanVeto;
            default -> false;
        };
    }

    private static String nullableString(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private ItemStack buildItem(Map<String, Object> item) {
        return switch (view) {
            case 0 -> buildIdentityItem(item);
            case 1 -> buildElectionItem(item);
            case 2 -> buildProposalItem(item);
            default -> new ItemStack(Material.BARRIER);
        };
    }

    private ItemStack buildIdentityItem(Map<String, Object> item) {
        String type = String.valueOf(item.getOrDefault("type", "identity"));
        if ("composition".equals(type)) {
            ItemStack stack = new ItemStack(Material.BOOKSHELF);
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return stack;
            meta.displayName(Component.text("§5🏛 元老院构成"));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("执政官: " + item.get("consul"), NamedTextColor.GOLD));
            lore.add(Component.text("元老: " + item.get("senators"), NamedTextColor.LIGHT_PURPLE));
            lore.add(Component.text("保民官: " + item.get("tribunes"), NamedTextColor.BLUE));
            lore.add(Component.text("骑士: " + item.get("equestrians"), NamedTextColor.GREEN));
            meta.lore(lore);
            stack.setItemMeta(meta);
            return stack;
        }

        // Identity
        boolean hasOffice = !"平民".equals(item.get("office"));
        Material icon = hasOffice ? Material.GOLDEN_HELMET : Material.LEATHER_HELMET;
        ItemStack stack = new ItemStack(icon);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String office = String.valueOf(item.get("office"));
        String color = switch (office) {
            case "CONSUL" -> "§6§l";
            case "SENATOR" -> "§d";
            case "TRIBUNE" -> "§9";
            case "EQUESTRIAN" -> "§a";
            default -> "§7";
        };
        meta.displayName(Component.text(color + "👤 " + office));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("元老: " + yn(item.get("isSenator")), NamedTextColor.GRAY));
        lore.add(Component.text("执政官: " + yn(item.get("isConsul")), NamedTextColor.GRAY));
        lore.add(Component.text("保民官: " + yn(item.get("isTribune")), NamedTextColor.GRAY));
        lore.add(Component.text("骑士: " + yn(item.get("isEquestrian")), NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(Component.text("可提案: " + yn(item.get("canPropose")), NamedTextColor.GRAY));
        lore.add(Component.text("可表决: " + yn(item.get("canVote")), NamedTextColor.GRAY));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildElectionItem(Map<String, Object> item) {
        String type = String.valueOf(item.getOrDefault("type", "candidate"));
        if ("election_info".equals(type)) {
            ItemStack stack = new ItemStack(Material.CLOCK);
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return stack;
            long endsAt = ((Number) item.get("ends_at")).longValue();
            meta.displayName(Component.text("§e🗳 保民官选举进行中"));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("截止: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                    .format(new java.util.Date(endsAt * 1000)), NamedTextColor.GRAY));
            if (item.get("my_vote") != null)
                lore.add(Component.text("你已投: " + item.get("my_vote"), NamedTextColor.GREEN));
            else
                lore.add(Component.text("你尚未投票", NamedTextColor.RED));
            meta.lore(lore);
            stack.setItemMeta(meta);
            return stack;
        }

        String name = String.valueOf(item.getOrDefault("candidate_name", "?"));
        int votes = item.get("votes") != null ? ((Number) item.get("votes")).intValue() : 0;
        String myVote = String.valueOf(item.getOrDefault("my_vote", ""));

        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        boolean isMyVote = name.equals(myVote);
        meta.displayName(Component.text((isMyVote ? "§a§l" : "§e") + "🗳 " + name));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("得票数: " + votes, NamedTextColor.GOLD));
        if (isMyVote) lore.add(Component.text("§a 你已投给此人", NamedTextColor.GREEN));
        lore.add(Component.empty());
        lore.add(Component.text("§a§l点击投票", NamedTextColor.YELLOW));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildProposalItem(Map<String, Object> p) {
        // NOTE: Keys are camelCase from Proposal.toMap()
        String status = String.valueOf(p.getOrDefault("status", "?"));
        Material material = switch (status) {
            case "ENACTED" -> Material.ENCHANTED_BOOK;
            case "REJECTED", "VETOED", "ABANDONED" -> Material.BOOK;
            default -> Material.WRITABLE_BOOK;
        };
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String title = String.valueOf(p.getOrDefault("title", "无标题"));
        String type = String.valueOf(p.getOrDefault("proposalType", "?"));
        String proposer = String.valueOf(p.getOrDefault("proposerName", "?"));
        String id = String.valueOf(p.getOrDefault("id", "?"));
        String proposerUuid = nullableString(p.get("proposerUuid"));

        String statusColor = switch (status) {
            case "ENACTED" -> "§a"; case "SENATE_VOTING" -> "§e";
            case "TRIBUNE_REVIEW" -> "§9"; case "SENATE_OVERRIDE" -> "§6";
            case "VETOED", "REJECTED", "ABANDONED" -> "§c";
            default -> "§7";
        };
        meta.displayName(Component.text("§e📜 " + title));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("类型: " + proposalTypeName(type), NamedTextColor.GRAY));
        lore.add(Component.text("状态: " + statusColor + proposalStatusName(status), NamedTextColor.GRAY));
        lore.add(Component.text("提案人: " + proposer, NamedTextColor.GRAY));
        lore.add(Component.text("ID: " + id, NamedTextColor.DARK_GRAY));

        String resultSummary = nullableString(p.get("resultSummary"));
        if (resultSummary != null) {
            lore.add(Component.text("结果: " + resultSummary, NamedTextColor.GRAY));
        }
        String voteSummary = nullableString(p.get("voteSummary"));
        if (voteSummary != null && APPEAL_STAGES.contains(status)) {
            lore.add(Component.text("进展: " + voteSummary, NamedTextColor.AQUA));
        }
        if (p.get("stageDeadlineAt") instanceof Number deadline && deadline.longValue() > 0
                && SENATE_VOTE_STAGES.contains(status)) {
            lore.add(Component.text("截止: " + proposalDeadlineText(deadline.longValue()), NamedTextColor.GOLD));
        }

        switch (status) {
            case "PROPOSED" -> {
                lore.add(Component.empty());
                boolean isProposer = viewerUuid != null && proposerUuid != null
                        && viewerUuid.toString().equals(proposerUuid);
                if (viewerCanPropose && isProposer) {
                    lore.add(Component.text("§a§l左键发起元老院表决", NamedTextColor.GREEN));
                } else {
                    lore.add(Component.text("等待提案人发起表决", NamedTextColor.DARK_GRAY));
                }
            }
            case "SENATE_VOTING", "SENATE_OVERRIDE" -> {
                lore.add(Component.empty());
                if (viewerCanVoteInSenate) {
                    lore.add(Component.text("§a§l左键赞成 §c§l右键反对 §7§l中键弃权", NamedTextColor.YELLOW));
                } else {
                    lore.add(Component.text("仅元老或执政官可表决", NamedTextColor.DARK_GRAY));
                }
            }
            case "TRIBUNE_REVIEW" -> {
                lore.add(Component.empty());
                if (viewerCanVeto) {
                    lore.add(Component.text("§a§l左键批准 §c§l右键否决", NamedTextColor.YELLOW));
                } else {
                    lore.add(Component.text("等待保民官审查", NamedTextColor.DARK_GRAY));
                }
            }
            case "VETOED" -> {
                lore.add(Component.empty());
                if (viewerCanVoteInSenate) {
                    lore.add(Component.text("§6§l左键发起覆议", NamedTextColor.GOLD));
                } else {
                    lore.add(Component.text("元老院议员可发起覆议", NamedTextColor.DARK_GRAY));
                }
            }
        }
        if (canViewerAppeal(status, proposerUuid)) {
            lore.add(Component.text("§b§lShift+左键 发起全服呼吁", NamedTextColor.AQUA));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private String proposalStatusName(String status) {
        return switch (status) {
            case "PROPOSED" -> "草案";
            case "SENATE_VOTING" -> "元老院表决中";
            case "TRIBUNE_REVIEW" -> "保民官审查";
            case "APPROVED" -> "已批准";
            case "ENACTED" -> "已颁布";
            case "REJECTED" -> "元老院否决";
            case "VETOED" -> "保民官否决";
            case "SENATE_OVERRIDE" -> "覆议中";
            case "OVERRIDDEN" -> "覆议通过";
            case "ABANDONED" -> "覆议失败";
            default -> status;
        };
    }

    private String proposalTypeName(String type) {
        return switch (type) {
            case "GENERAL" -> "普通决议";
            case "SET_TAX_RATE" -> "设置税率";
            case "SET_TAX_BRACKET" -> "阶梯税率";
            case "SET_CB_RATES" -> "央行利率";
            case "CB_INJECT" -> "央行注资";
            case "SET_OFFICIAL_PRICE" -> "官方定价";
            case "RE_ZONE_ADMIN" -> "区域规划";
            default -> type;
        };
    }

    private String proposalDeadlineText(long deadlineAt) {
        long remaining = deadlineAt - System.currentTimeMillis() / 1000;
        String absolute = new java.text.SimpleDateFormat("MM-dd HH:mm")
                .format(new java.util.Date(deadlineAt * 1000));
        if (remaining <= 0) return absolute + "（等待结算）";
        long hours = remaining / 3600;
        long minutes = (remaining % 3600) / 60;
        return absolute + "（剩余 " + (hours > 0 ? hours + "小时" : "") + Math.max(1, minutes) + "分钟）";
    }

    private String yn(Object b) {
        return b instanceof Boolean bb && bb ? "§a✔" : "§c✘";
    }

    private ItemStack emptyHint() {
        String msg = switch (view) {
            case 0 -> "§7政治模块未加载，无法获取身份信息";
            case 1 -> "§7暂无选举信息";
            case 2 -> "§7暂无提案";
            default -> "§7无数据";
        };
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.displayName(Component.text(msg)); item.setItemMeta(meta); }
        return item;
    }

    private ItemStack navButton(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            if (lore.length > 0) {
                List<Component> loreList = new ArrayList<>();
                for (String s : lore) loreList.add(Component.text(s, NamedTextColor.GRAY));
                meta.lore(loreList);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fillEmpty() {
        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        if (gm != null) { gm.displayName(Component.text(" ")); glass.setItemMeta(gm); }
        for (int i = 0; i < ROWS * 9; i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, glass.clone());
        }
    }

    @Override public @NotNull Inventory getInventory() { return inventory; }

    // ---- Listener ----

    public static class Listener implements org.bukkit.event.Listener {

        private final KsEco plugin;

        public Listener(KsEco plugin) { this.plugin = plugin; }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getInventory().getHolder() instanceof PoliticGui gui)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;

            // getSlot() is relative to the clicked inventory. Using it here allowed a
            // player-inventory click to trigger a same-numbered GUI button.
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= gui.getInventory().getSize()) return;

            // View tabs (0-2)
            if (slot >= 0 && slot <= 2) {
                if (slot != gui.view) {
                    gui.view = slot; gui.page = 0; gui.loadData(player); gui.build();
                    player.openInventory(gui.getInventory());
                }
                return;
            }

            // Content slots
            if (slot >= 9 && slot < 9 + PAGE_SIZE) {
                int index = gui.page * PAGE_SIZE + (slot - 9);
                if (index < gui.items.size()) {
                    Map<String, Object> item = gui.items.get(index);
                    switch (gui.view) {
                        case 1 -> {
                            // The clock/info row shares the content area but is not a candidate.
                            if (!"candidate".equals(item.get("type"))) return;
                            doElectionVote(player, nullableString(item.get("election_id")),
                                    nullableString(item.get("candidate_uuid")),
                                    nullableString(item.get("candidate_name")), gui);
                        }
                        case 2 -> handleProposalClick(player, item, event, gui);
                    }
                }
                return;
            }

            switch (slot) {
                case 45 -> { if (gui.page > 0) { gui.page--; gui.build(); player.openInventory(gui.getInventory()); } }
                case 47 -> {
                    if (gui.view == 2 && gui.viewerCanPropose) {
                        player.closeInventory();
                        pendingProposal.put(player.getUniqueId(), new PendingProposal(1, null, null, null));
                        player.sendMessage("§a请选择提案类型（输入数字）：§f1§7=普通决议  §f2§7=设置税率  §f3§7=央行利率，或输入 cancel 取消");
                    }
                }
                case 49 -> { player.closeInventory(); new EcoGuiMainMenu(plugin).open(player); }
                case 53 -> {
                    if ((gui.page + 1) * PAGE_SIZE < gui.items.size()) {
                        gui.page++; gui.build(); player.openInventory(gui.getInventory());
                    }
                }
            }
        }

        private void handleProposalClick(Player player, Map<String, Object> item,
                                         InventoryClickEvent event, PoliticGui gui) {
            String proposalId = nullableString(item.get("id"));
            if (proposalId == null) {
                player.sendMessage("§c操作失败：提案 ID 缺失。");
                return;
            }

            try {
                Object proposalManager = gui.getProposalManager();
                if (proposalManager == null) {
                    player.sendMessage("§c提案模块未加载。");
                    return;
                }
                Object proposal = proposalManager.getClass().getMethod("getProposal", String.class)
                        .invoke(proposalManager, proposalId);
                if (proposal == null) {
                    player.sendMessage("§c提案不存在或已被移除。");
                    refreshAndOpen(gui, player);
                    return;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> fresh = (Map<String, Object>) proposal.getClass().getMethod("toMap").invoke(proposal);
                String status = String.valueOf(fresh.getOrDefault("status", ""));

                if (APPEAL_STAGES.contains(status) && event.isShiftClick() && event.isLeftClick()) {
                    appealProposal(player, proposalId, gui);
                    return;
                }

                switch (status) {
                    case "PROPOSED" -> {
                        if (event.isLeftClick()) startProposalVote(player, proposalId, gui);
                    }
                    case "SENATE_VOTING", "SENATE_OVERRIDE" -> {
                        String vote = voteForClick(event);
                        if (vote != null) castProposalVote(player, proposalId, status, vote, gui);
                    }
                    case "TRIBUNE_REVIEW" -> {
                        if (event.isLeftClick()) reviewProposal(player, proposalId, true, gui);
                        else if (event.isRightClick()) reviewProposal(player, proposalId, false, gui);
                    }
                    case "VETOED" -> {
                        if (event.isLeftClick()) startOverride(player, proposalId, gui);
                    }
                    default -> { /* Terminal/non-interactive state. */ }
                }
            } catch (Exception e) {
                fail(player, "操作提案", e);
            }
        }

        private String voteForClick(InventoryClickEvent event) {
            if (event.isLeftClick()) return "YES";
            if (event.isRightClick()) return "NO";
            if (event.getClick() == ClickType.MIDDLE) return "ABSTAIN";
            return null;
        }

        private void doElectionVote(Player player, String electionId, String candidateUuid,
                                    String candidateName, PoliticGui gui) {
            if (electionId == null) {
                player.sendMessage("§c当前没有进行中的保民官选举。");
                return;
            }
            if (candidateUuid == null) {
                player.sendMessage("§c候选人信息不完整，请重新打开界面。");
                return;
            }

            try {
                UUID parsedCandidate = UUID.fromString(candidateUuid);
                Object politicManager = gui.getPoliticManager();
                if (politicManager == null) throw new IllegalStateException("政治模块未加载");
                String resolvedName = candidateName != null ? candidateName : candidateUuid;
                Object result = politicManager.getClass().getMethod("castTribuneElectionVote",
                                String.class, UUID.class, String.class, UUID.class, String.class)
                        .invoke(politicManager, electionId, player.getUniqueId(), player.getName(),
                                parsedCandidate, resolvedName);
                requireSuccess(result, "选举投票失败");
                player.sendMessage("§a已投票给 " + resolvedName + "。");
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f);
                refreshAndOpen(gui, player);
            } catch (IllegalArgumentException e) {
                player.sendMessage("§c候选人 UUID 无效，请重新打开界面。");
            } catch (Exception e) {
                fail(player, "选举投票", e);
            }
        }

        private void appealProposal(Player player, String proposalId, PoliticGui gui) {
            try {
                Object proposalManager = requireManager(gui.getProposalManager(), "提案模块未加载");
                Object result = proposalManager.getClass().getMethod("appealForProposal",
                                String.class, UUID.class, String.class)
                        .invoke(proposalManager, proposalId, player.getUniqueId(), player.getName());
                requireSuccess(result, "发起全服呼吁失败");
                String message = resultDetail(result);
                player.sendMessage("§a" + (message != null ? message : "全服呼吁已发布。"));
                player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 0.7f, 1.2f);
                refreshAndOpen(gui, player);
            } catch (Exception e) {
                fail(player, "发起全服呼吁", e);
            }
        }

        private void startProposalVote(Player player, String proposalId, PoliticGui gui) {
            try {
                Object proposalManager = requireManager(gui.getProposalManager(), "提案模块未加载");
                Object result = proposalManager.getClass().getMethod("transitionProposal",
                                String.class, String.class, UUID.class, String.class)
                        .invoke(proposalManager, proposalId, "SENATE_VOTING",
                                player.getUniqueId(), player.getName());
                requireSuccess(result, "发起表决失败");
                player.sendMessage("§a提案已进入元老院表决。");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.5f);
                refreshAndOpen(gui, player);
            } catch (Exception e) {
                fail(player, "发起表决", e);
            }
        }

        private void castProposalVote(Player player, String proposalId, String stage,
                                      String vote, PoliticGui gui) {
            if (!SENATE_VOTE_STAGES.contains(stage)) return;
            try {
                Object politicManager = requireManager(gui.getPoliticManager(), "政治模块未加载");
                boolean canVote = (boolean) politicManager.getClass()
                        .getMethod("canVoteInSenate", UUID.class).invoke(politicManager, player.getUniqueId());
                if (!canVote) throw new IllegalStateException("只有元老或执政官可以表决");
                String office = (String) politicManager.getClass()
                        .getMethod("getPlayerOffice", UUID.class).invoke(politicManager, player.getUniqueId());
                if (office == null || office.isBlank()) throw new IllegalStateException("无法确定你的投票职务");

                Object voteManager = requireManager(gui.getVoteManager(), "投票模块未加载");
                Object result = voteManager.getClass().getMethod("castVote", String.class, UUID.class,
                                String.class, String.class, String.class, String.class)
                        .invoke(voteManager, proposalId, player.getUniqueId(), player.getName(),
                                office, vote, stage);
                requireSuccess(result, "表决失败");

                Object tally = result.getClass().getMethod("tally").invoke(result);
                String advanceWarning = advanceIfQuorum(gui, proposalId, tally);
                String voteName = switch (vote) {
                    case "YES" -> "赞成";
                    case "NO" -> "反对";
                    default -> "弃权";
                };
                player.sendMessage("§a已投" + voteName + "。" + advanceWarning);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f);
                refreshAndOpen(gui, player);
            } catch (Exception e) {
                fail(player, "提案表决", e);
            }
        }

        private String advanceIfQuorum(PoliticGui gui, String proposalId, Object tally) throws Exception {
            if (tally == null) return "";
            boolean quorumMet = (boolean) tally.getClass().getMethod("quorumMet").invoke(tally);
            if (!quorumMet) return "";
            Object proposalManager = requireManager(gui.getProposalManager(), "提案模块未加载");
            Object transition = proposalManager.getClass()
                    .getMethod("autoAdvanceAfterVote", String.class, tally.getClass())
                    .invoke(proposalManager, proposalId, tally);
            if (resultSucceeded(transition)) return " §e已达法定人数，流程已自动推进。";
            String error = resultDetail(transition);
            plugin.getLogger().warning("PoliticGui 表决已记录但自动推进失败: " + error);
            return " §c投票已记录，但流程推进失败: " + error;
        }

        private void reviewProposal(Player player, String proposalId, boolean approve, PoliticGui gui) {
            try {
                Object politicManager = requireManager(gui.getPoliticManager(), "政治模块未加载");
                boolean canVeto = (boolean) politicManager.getClass()
                        .getMethod("canVeto", UUID.class).invoke(politicManager, player.getUniqueId());
                if (!canVeto) throw new IllegalStateException("只有保民官可以审查提案");

                Object voteManager = requireManager(gui.getVoteManager(), "投票模块未加载");
                Object voteResult = voteManager.getClass().getMethod("castVote", String.class, UUID.class,
                                String.class, String.class, String.class, String.class)
                        .invoke(voteManager, proposalId, player.getUniqueId(), player.getName(),
                                "TRIBUNE", approve ? "YES" : "NO", "TRIBUNE_REVIEW");
                requireSuccess(voteResult, "保民官投票失败");

                Object proposalManager = requireManager(gui.getProposalManager(), "提案模块未加载");
                String newState = approve ? "APPROVED" : "VETOED";
                Object transition = proposalManager.getClass().getMethod("transitionProposal",
                                String.class, String.class, UUID.class, String.class)
                        .invoke(proposalManager, proposalId, newState,
                                player.getUniqueId(), player.getName());
                requireSuccess(transition, approve ? "批准提案失败" : "否决提案失败");

                if (approve) {
                    Object enact = proposalManager.getClass().getMethod("transitionProposal",
                                    String.class, String.class, UUID.class, String.class)
                            .invoke(proposalManager, proposalId, "ENACTED",
                                    player.getUniqueId(), "SYSTEM");
                    requireSuccess(enact, "提案已批准，但颁布失败");
                }
                player.sendMessage(approve ? "§a提案已批准并颁布。" : "§c提案已被保民官否决。");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, approve ? 1.7f : 0.7f);
                refreshAndOpen(gui, player);
            } catch (Exception e) {
                fail(player, "保民官审查", e);
            }
        }

        private void startOverride(Player player, String proposalId, PoliticGui gui) {
            try {
                Object politicManager = requireManager(gui.getPoliticManager(), "政治模块未加载");
                boolean canVote = (boolean) politicManager.getClass()
                        .getMethod("canVoteInSenate", UUID.class).invoke(politicManager, player.getUniqueId());
                if (!canVote) throw new IllegalStateException("只有元老院议员可以发起覆议");

                Object proposalManager = requireManager(gui.getProposalManager(), "提案模块未加载");
                Object transition = proposalManager.getClass().getMethod("transitionProposal",
                                String.class, String.class, UUID.class, String.class)
                        .invoke(proposalManager, proposalId, "SENATE_OVERRIDE",
                                player.getUniqueId(), player.getName());
                requireSuccess(transition, "发起覆议失败");

                String office = (String) politicManager.getClass()
                        .getMethod("getPlayerOffice", UUID.class).invoke(politicManager, player.getUniqueId());
                Object voteManager = requireManager(gui.getVoteManager(), "投票模块未加载");
                Object voteResult = voteManager.getClass().getMethod("castVote", String.class, UUID.class,
                                String.class, String.class, String.class, String.class)
                        .invoke(voteManager, proposalId, player.getUniqueId(), player.getName(),
                                office, "YES", "SENATE_OVERRIDE");
                requireSuccess(voteResult, "覆议已启动，但发起人自动赞成投票失败");

                player.sendMessage("§a覆议已启动，你已自动投下赞成票。");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.2f);
                refreshAndOpen(gui, player);
            } catch (Exception e) {
                fail(player, "发起覆议", e);
            }
        }

        private static Object requireManager(Object manager, String message) {
            if (manager == null) throw new IllegalStateException(message);
            return manager;
        }

        private static void requireSuccess(Object result, String fallback) throws Exception {
            if (resultSucceeded(result)) return;
            String detail = resultDetail(result);
            throw new IllegalStateException(detail != null ? detail : fallback);
        }

        private static boolean resultSucceeded(Object result) throws Exception {
            return result != null && Boolean.TRUE.equals(result.getClass().getMethod("success").invoke(result));
        }

        private static String resultDetail(Object result) {
            if (result == null) return null;
            for (String accessor : List.of("error", "message")) {
                try {
                    String detail = nullableString(result.getClass().getMethod(accessor).invoke(result));
                    if (detail != null) return detail;
                } catch (Exception ignored) {}
            }
            return null;
        }

        private static void refreshAndOpen(PoliticGui gui, Player player) {
            gui.loadData(player);
            int maxPage = gui.items.isEmpty() ? 0 : (gui.items.size() - 1) / PAGE_SIZE;
            gui.page = Math.min(gui.page, maxPage);
            gui.build();
            player.openInventory(gui.getInventory());
        }

        private static void fail(Player player, String action, Exception exception) {
            Throwable cause = exception;
            while (cause.getCause() != null) cause = cause.getCause();
            String message = nullableString(cause.getMessage());
            player.sendMessage("§c" + action + "失败: " + (message != null ? message : "未知错误"));
        }
    }

    // ---- 提案聊天向导（G7）：GENERAL / SET_TAX_RATE / SET_CB_RATES ----

    record PendingProposal(int step, String type, String field1, String field2) {}
    static final Map<UUID, PendingProposal> pendingProposal = new java.util.concurrent.ConcurrentHashMap<>();

    /** createProposal 反射调用；服务端自带 payload 校验与长度限制。 */
    private static void createProposalReflect(KsEco plugin, Player player, String title, String description,
                                              String proposalType, String payloadJson) {
        Object pm = null;
        var mod = plugin.extraModuleLoader().getModule("ks-eco-politic");
        if (mod != null) {
            try { pm = mod.getClass().getMethod("politicManager").invoke(mod); } catch (Exception ignored) {}
        }
        String office = "";
        boolean canPropose = false;
        if (pm != null) {
            try {
                office = String.valueOf(pm.getClass().getMethod("getPlayerOffice", UUID.class).invoke(pm, player.getUniqueId()));
                canPropose = (boolean) pm.getClass().getMethod("canPropose", UUID.class).invoke(pm, player.getUniqueId());
            } catch (Exception ignored) {}
        }
        if (!canPropose && !player.hasPermission("kseco.admin")) {
            player.sendMessage("§c你没有提案权（需执政官/骑士身份）。"); return;
        }
        Object result = plugin.callExtraManager("ks-eco-politic", "proposalManager", "createProposal",
                new Class<?>[]{String.class, String.class, String.class, String.class, String.class,
                        UUID.class, String.class, String.class},
                title, description, proposalType, "", payloadJson,
                player.getUniqueId(), player.getName(), office);
        boolean ok = false; String err = null, id = null;
        if (result != null) {
            try {
                ok = (boolean) result.getClass().getMethod("success").invoke(result);
                id = (String) result.getClass().getMethod("id").invoke(result);
                err = (String) result.getClass().getMethod("error").invoke(result);
            } catch (Exception ignored) {}
        }
        if (ok) {
            player.sendMessage("§a提案已提交！ID: " + id + "，请在网页端或提案列表中「发起表决」进入元老院流程。");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 2.0f);
        } else {
            player.sendMessage("§c提案失败" + (err != null ? ": " + err : "（政治模块未加载）"));
        }
    }

    public static class ChatListener implements org.bukkit.event.Listener {

        private final KsEco plugin;

        public ChatListener(KsEco plugin) { this.plugin = plugin; }

        @EventHandler
        public void onChat(AsyncPlayerChatEvent event) {
            UUID playerId = event.getPlayer().getUniqueId();
            PendingProposal pending = pendingProposal.remove(playerId);
            if (pending == null) return;

            event.setCancelled(true);
            String msg = event.getMessage().trim();
            plugin.scheduler().runPlayer(playerId, () -> {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null) return;
                if (msg.equalsIgnoreCase("cancel")) {
                    player.sendMessage("§c已取消。");
                    new PoliticGui(plugin).open(player);
                    return;
                }

                switch (pending.step) {
                    case 1 -> {
                        String type = switch (msg) {
                            case "1" -> "GENERAL";
                            case "2" -> "SET_TAX_RATE";
                            case "3" -> "SET_CB_RATES";
                            default -> null;
                        };
                        if (type == null) {
                            player.sendMessage("§c请输入 1 / 2 / 3 选择类型。");
                            pendingProposal.put(player.getUniqueId(), new PendingProposal(1, null, null, null));
                            return;
                        }
                        pendingProposal.put(player.getUniqueId(), new PendingProposal(2, type, null, null));
                        switch (type) {
                            case "GENERAL" -> player.sendMessage("§a请输入提案标题（≤128字）：");
                            case "SET_TAX_RATE" -> player.sendMessage("§a请输入税种（如 MARKET_TRADE / ENTERPRISE_TAX / DIVIDEND_TAX / BANK_INTEREST）：");
                            case "SET_CB_RATES" -> player.sendMessage("§a请输入央行基准利率（0-1，如 0.035）：");
                        }
                    }
                    case 2 -> {
                        switch (pending.type) {
                            case "GENERAL" -> {
                                if (msg.isEmpty() || msg.length() > 128) {
                                    player.sendMessage("§c标题不能为空且不超过128字。");
                                    pendingProposal.put(player.getUniqueId(), new PendingProposal(2, pending.type, null, null));
                                    return;
                                }
                                pendingProposal.put(player.getUniqueId(), new PendingProposal(3, pending.type, msg, null));
                                player.sendMessage("§a请输入提案说明（≤4096字，输入 - 跳过）：");
                            }
                            case "SET_TAX_RATE" -> {
                                pendingProposal.put(player.getUniqueId(), new PendingProposal(3, pending.type, msg.toUpperCase(java.util.Locale.ROOT), null));
                                player.sendMessage("§a请输入目标税率（0-1，如 0.05 表示 5%）：");
                            }
                            case "SET_CB_RATES" -> {
                                try {
                                    double v = Double.parseDouble(msg);
                                    if (v < 0 || v > 1) throw new NumberFormatException();
                                } catch (NumberFormatException e) {
                                    player.sendMessage("§c基准利率必须是 0-1 之间的数字。");
                                    pendingProposal.put(player.getUniqueId(), new PendingProposal(2, pending.type, null, null));
                                    return;
                                }
                                pendingProposal.put(player.getUniqueId(), new PendingProposal(3, pending.type, msg, null));
                                player.sendMessage("§a请输入准备金率（0-1，如 0.10）：");
                            }
                        }
                    }
                    case 3 -> {
                        switch (pending.type) {
                            case "GENERAL" -> {
                                String desc = msg.equals("-") ? "" : msg;
                                createProposalReflect(plugin, player, pending.field1, desc, "GENERAL", "{}");
                            }
                            case "SET_TAX_RATE" -> {
                                double rate;
                                try {
                                    rate = Double.parseDouble(msg);
                                    if (rate < 0 || rate > 1) throw new NumberFormatException();
                                } catch (NumberFormatException e) {
                                    player.sendMessage("§c税率必须是 0-1 之间的数字。");
                                    pendingProposal.put(player.getUniqueId(), new PendingProposal(3, pending.type, pending.field1, null));
                                    return;
                                }
                                String title = "调整 " + pending.field1 + " 税率至 " + String.format(java.util.Locale.ROOT, "%.2f%%", rate * 100);
                                String payload = "{\"category\":\"" + pending.field1 + "\",\"rate\":" + rate + "}";
                                createProposalReflect(plugin, player, title, title, "SET_TAX_RATE", payload);
                            }
                            case "SET_CB_RATES" -> {
                                double base, reserve;
                                try {
                                    base = Double.parseDouble(pending.field1);
                                    reserve = Double.parseDouble(msg);
                                    if (reserve < 0 || reserve > 1) throw new NumberFormatException();
                                } catch (NumberFormatException e) {
                                    player.sendMessage("§c准备金率必须是 0-1 之间的数字。");
                                    pendingProposal.put(player.getUniqueId(), new PendingProposal(3, pending.type, pending.field1, null));
                                    return;
                                }
                                String title = String.format(java.util.Locale.ROOT,
                                        "调整央行利率：基准 %.2f%% / 准备金 %.2f%%", base * 100, reserve * 100);
                                String payload = "{\"baseRate\":" + base + ",\"reserveRequirement\":" + reserve + "}";
                                createProposalReflect(plugin, player, title, title, "SET_CB_RATES", payload);
                            }
                        }
                        new PoliticGui(plugin).open(player);
                    }
                }
            });
        }
    }
}
