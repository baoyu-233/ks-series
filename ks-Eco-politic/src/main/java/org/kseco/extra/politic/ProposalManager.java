package org.kseco.extra.politic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.kseco.KsEco;

import java.sql.*;
import java.util.*;

/**
 * 提案管理器。
 *
 * 实现完整的 9 状态立法状态机（PROPOSED → ENACTED/REJECTED/ABANDONED），
 * 包含保民官一票否决 + 元老院覆议全票强行推行。
 */
public final class ProposalManager {

    private final KsEco eco;
    private final PoliticManager politicManager;
    private final VoteManager voteManager;
    private final Map<String, Integer> lastAnnouncedVoteCount = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> lastProgressAnnouncementAt = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> deadlineWarnings = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private int announcementTaskId = -1;

    // 合法状态转换
    private static final Map<String, List<String>> ALLOWED_TRANSITIONS = new LinkedHashMap<>();

    static {
        ALLOWED_TRANSITIONS.put("PROPOSED",       List.of("SENATE_VOTING"));
        ALLOWED_TRANSITIONS.put("SENATE_VOTING",  List.of("TRIBUNE_REVIEW", "REJECTED"));
        ALLOWED_TRANSITIONS.put("TRIBUNE_REVIEW", List.of("APPROVED", "VETOED"));
        ALLOWED_TRANSITIONS.put("APPROVED",       List.of("ENACTED"));
        ALLOWED_TRANSITIONS.put("VETOED",         List.of("SENATE_OVERRIDE"));
        ALLOWED_TRANSITIONS.put("SENATE_OVERRIDE",List.of("OVERRIDDEN", "ABANDONED"));
        ALLOWED_TRANSITIONS.put("OVERRIDDEN",     List.of("ENACTED"));
        // ENACTED, REJECTED, ABANDONED 为终态，不可再转换
    }

    public ProposalManager(KsEco eco, PoliticManager politicManager, VoteManager voteManager) {
        this.eco = eco;
        this.politicManager = politicManager;
        this.voteManager = voteManager;
    }

    public synchronized void init() {
        if (announcementTaskId >= 0) Bukkit.getScheduler().cancelTask(announcementTaskId);
        initializeMissingStageDeadlines();
        announcementTaskId = Bukkit.getScheduler().runTaskTimer(
                eco, this::checkVotingAnnouncements, 400L, 1200L).getTaskId();
    }

    public synchronized void shutdown() {
        if (announcementTaskId >= 0) {
            Bukkit.getScheduler().cancelTask(announcementTaskId);
            announcementTaskId = -1;
        }
    }

    // ================================================================
    // CRUD
    // ================================================================

    /**
     * 创建提案。
     */
    public CreateResult createProposal(String title, String description, String proposalType,
                                        String targetEndpoint, String payloadJson,
                                        UUID proposerUuid, String proposerName, String proposerOffice) {
        if (title == null || title.isBlank() || title.length() > 128) return CreateResult.fail("提案标题长度无效");
        if (description != null && description.length() > 4096) return CreateResult.fail("提案说明过长");
        Map<String, Object> parsedPayload;
        try {
            parsedPayload = new com.google.gson.Gson().fromJson(payloadJson == null ? "{}" : payloadJson, Map.class);
        } catch (RuntimeException e) {
            return CreateResult.fail("提案参数不是有效 JSON");
        }
        String validationError = validateProposalPayload(proposalType, parsedPayload == null ? Map.of() : parsedPayload);
        if (validationError != null) return CreateResult.fail(validationError);
        String id = UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;

        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return CreateResult.fail("数据库连接失败");

            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ks_politic_proposals " +
                "(id, title, description, proposal_type, target_endpoint, payload_json, " +
                " proposer_uuid, proposer_name, proposer_office, status, created_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,'PROPOSED',?)")) {
                ps.setString(1, id);
                ps.setString(2, title);
                ps.setString(3, description != null ? description : "");
                ps.setString(4, proposalType);
                ps.setString(5, targetEndpoint != null ? targetEndpoint : "");
                ps.setString(6, payloadJson != null ? payloadJson : "{}");
                ps.setString(7, proposerUuid.toString());
                ps.setString(8, proposerName);
                ps.setString(9, proposerOffice);
                ps.setLong(10, now);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[政治系统] 创建提案失败: " + e.getMessage());
            return CreateResult.fail("数据库写入失败");
        }

        return CreateResult.success(id, title);
    }

    /**
     * 获取单个提案。
     */
    public Proposal getProposal(String proposalId) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM ks_politic_proposals WHERE id=?")) {
                ps.setString(1, proposalId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return new Proposal(rs);
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[政治系统] 读取提案失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 列出提案（可按状态过滤）。
     */
    public List<Proposal> listProposals(String statusFilter, String proposerUuidFilter, int limit) {
        List<Proposal> out = new ArrayList<>();
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;

            StringBuilder sql = new StringBuilder("SELECT * FROM ks_politic_proposals WHERE 1=1");
            List<String> params = new ArrayList<>();
            if (statusFilter != null && !statusFilter.isEmpty() && !"all".equalsIgnoreCase(statusFilter)) {
                sql.append(" AND status=?");
                params.add(statusFilter);
            }
            if (proposerUuidFilter != null && !proposerUuidFilter.isEmpty()) {
                sql.append(" AND proposer_uuid=?");
                params.add(proposerUuidFilter);
            }
            sql.append(" ORDER BY created_at DESC LIMIT ?");
            params.add(String.valueOf(Math.min(limit, 200)));

            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    if (i == params.size() - 1) ps.setInt(i + 1, Integer.parseInt(params.get(i)));
                    else ps.setString(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Proposal(rs));
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[政治系统] 列出提案失败: " + e.getMessage());
        }
        return out;
    }

    // ================================================================
    // 状态机引擎（核心）
    // ================================================================

    /**
     * 提案状态转换。
     *
     * 以下转换必须通过此方法：
     * - PROPOSED → SENATE_VOTING（发起表决）
     * - TRIBUNE_REVIEW → APPROVED / VETOED（保民官审查）
     * - VETOED → SENATE_OVERRIDE（发起覆议）
     *
     * 以下转换由计票完成后自动触发：
     * - SENATE_VOTING → TRIBUNE_REVIEW / REJECTED
     * - SENATE_OVERRIDE → OVERRIDDEN / ABANDONED
     * - APPROVED → ENACTED
     * - OVERRIDDEN → ENACTED
     */
    public synchronized TransitionResult transitionProposal(String proposalId, String newState,
                                                              UUID initiatorUuid, String initiatorName) {
        Proposal p = getProposal(proposalId);
        if (p == null) return TransitionResult.fail("提案不存在");

        String current = p.status;

        // 验证转换合法性
        List<String> allowed = ALLOWED_TRANSITIONS.get(current);
        if (allowed == null || !allowed.contains(newState)) {
            return TransitionResult.fail("不能从 " + current + " 转换到 " + newState);
        }

        // 各步守卫
        switch (newState) {
            case "SENATE_VOTING":
                if (!politicManager.canPropose(initiatorUuid) && !"SYSTEM".equals(initiatorName))
                    return TransitionResult.fail("只有执政官或骑士可以发起表决");
                if (!p.proposerUuid.equals(initiatorUuid.toString()) && !"SYSTEM".equals(initiatorName))
                    return TransitionResult.fail("只有提案提交者可以发起表决");
                break;

            case "TRIBUNE_REVIEW":
                if (current.equals("SENATE_VOTING")) {
                    // 自动转换 — 直接放行
                    break;
                }
                return TransitionResult.fail("不能手动转换到 TRIBUNE_REVIEW");

            case "REJECTED":
                if (current.equals("SENATE_VOTING")) break; // 自动 — 放行
                return TransitionResult.fail("不能手动转换为 REJECTED");

            case "APPROVED":
            case "VETOED":
                if (!politicManager.canVeto(initiatorUuid))
                    return TransitionResult.fail("只有保民官可以审查法案");
                break;

            case "SENATE_OVERRIDE":
                if (!politicManager.canVoteInSenate(initiatorUuid))
                    return TransitionResult.fail("只有元老院议员可以发起覆议");
                // 清除上一阶段的投票记录
                voteManager.clearStageVotes(proposalId, "SENATE_OVERRIDE");
                break;

            case "OVERRIDDEN":
            case "ABANDONED":
                if (current.equals("SENATE_OVERRIDE")) break; // 自动 — 放行
                return TransitionResult.fail("不能手动转换为 " + newState);

            case "ENACTED":
                // ENACTED 只能从 APPROVED 或 OVERRIDDEN 自动进入
                if (!current.equals("APPROVED") && !current.equals("OVERRIDDEN"))
                    return TransitionResult.fail("不能手动颁布");
                break;
        }

        // ENACTED：先执行 payload，失败则不标记为已颁布（避免"假颁布"——
        // 此前是先写 ENACTED 再 enact 且吞异常，导致法案没生效却显示已通过）
        if ("ENACTED".equals(newState)) {
            String enactErr = enact(p);
            if (enactErr != null) {
                return TransitionResult.fail("法案执行失败，未颁布: " + enactErr);
            }
        }

        // 执行状态更新
        p.status = newState;
        long now = System.currentTimeMillis() / 1000;
        if ("SENATE_VOTING".equals(newState) || "SENATE_OVERRIDE".equals(newState)) {
            int durationMinutes = votingDurationMinutes(newState);
            p.stageStartedAt = now;
            p.stageDeadlineAt = now + durationMinutes * 60L;
        } else {
            p.stageStartedAt = 0;
            p.stageDeadlineAt = 0;
        }
        if ("ENACTED".equals(newState)) p.enactedAt = now;

        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return TransitionResult.fail("数据库连接失败");

            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE ks_politic_proposals SET status=?, result_summary=?, enacted_at=?, " +
                        "stage_started_at=?, stage_deadline_at=? WHERE id=? AND status=?")) {
                ps.setString(1, newState);
                ps.setString(2, p.resultSummary != null ? p.resultSummary : "");
                ps.setLong(3, "ENACTED".equals(newState) ? now : p.enactedAt);
                ps.setLong(4, p.stageStartedAt);
                ps.setLong(5, p.stageDeadlineAt);
                ps.setString(6, proposalId);
                ps.setString(7, current);
                if (ps.executeUpdate() != 1) {
                    return TransitionResult.fail("提案状态已被其他操作更新，请刷新后重试");
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[政治系统] 状态更新失败: " + e.getMessage());
            return TransitionResult.fail("数据库写入失败");
        }

        // 推送 / 撤下 ks-core 公告栏（表决中、已颁布）
        publishToBulletin(p, newState);

        return TransitionResult.success(p, current, newState);
    }

    /** 提案状态变更时同步公告栏 + 游戏内广播（失败不影响立法）。 */
    private void publishToBulletin(Proposal p, String newState) {
        try {
            var bridge = eco.bridge();
            String ref = "prop:" + p.id;
            switch (newState) {
                case "SENATE_VOTING" -> {
                    markStageAnnouncementStart(p);
                    if (bridge != null) bridge.postAnnouncement("VOTING", ref,
                            "【元老院表决】" + p.title, votingBulletinBody(p, null), "元老院", 10, p.stageDeadlineAt);
                    broadcastOfficial("提案已进入元老院表决", NamedTextColor.YELLOW, p,
                            "类型: " + typeCn(p.proposalType) + " ｜ 截止: " + formatDeadline(p.stageDeadlineAt)
                                    + " ｜ 合资格元老请及时投票。", "打开 /eco gui 参与表决");
                }
                case "TRIBUNE_REVIEW" -> {
                    clearStageAnnouncementState(p.id);
                    if (bridge != null) bridge.postAnnouncement("REVIEW", ref,
                            "【待保民官审查】" + p.title,
                            "元老院表决已通过。" + resultLine(p) + "\n保民官请在 /eco gui 完成批准或否决。",
                            "元老院", 11, 0);
                    broadcastOfficial("元老院表决通过", NamedTextColor.GREEN, p,
                            resultLine(p) + " 保民官请进入元老院界面完成审查。", "打开 /eco gui 审查");
                }
                case "APPROVED" -> {
                    if (bridge != null) bridge.removeAnnouncement(ref);
                    broadcastOfficial("保民官已批准提案", NamedTextColor.GREEN, p,
                            "法案即将执行并正式颁布。", "打开 /eco gui 查看");
                }
                case "SENATE_OVERRIDE" -> {
                    markStageAnnouncementStart(p);
                    if (bridge != null) bridge.postAnnouncement("VOTING", ref,
                            "【元老院覆议】" + p.title, votingBulletinBody(p, null), "元老院", 12, p.stageDeadlineAt);
                    broadcastOfficial("元老院覆议已启动", NamedTextColor.RED, p,
                            "覆议必须由全体合资格元老一致赞成 ｜ 截止: " + formatDeadline(p.stageDeadlineAt),
                            "打开 /eco gui 参与覆议");
                }
                case "OVERRIDDEN" -> {
                    clearStageAnnouncementState(p.id);
                    if (bridge != null) bridge.removeAnnouncement(ref);
                    broadcastOfficial("覆议全票通过", NamedTextColor.GREEN, p,
                            resultLine(p) + " 法案将立即颁布。", "打开 /eco gui 查看");
                }
                case "ENACTED" -> {
                    clearStageAnnouncementState(p.id);
                    if (bridge != null) {
                        bridge.removeAnnouncement(ref);
                        bridge.removeAnnouncement("appeal:" + p.id);
                        bridge.postAnnouncement("LAW", "law:" + p.id,
                                "【已颁布】" + p.title,
                                typeCn(p.proposalType) + resultLine(p)
                                        + (p.description != null && !p.description.isEmpty() ? "\n" + p.description : ""),
                                "元老院", 5, 0);
                    }
                    broadcastOfficial("法案正式颁布", NamedTextColor.GREEN, p,
                            "类型: " + typeCn(p.proposalType) + resultLine(p), "打开 /eco gui 查看法案");
                }
                case "REJECTED" -> {
                    clearStageAnnouncementState(p.id);
                    if (bridge != null) {
                        bridge.removeAnnouncement(ref);
                        bridge.removeAnnouncement("appeal:" + p.id);
                    }
                    broadcastOfficial("元老院表决未通过", NamedTextColor.RED, p,
                            resultLine(p) + " 提案已结束。", "打开 /eco gui 查看结果");
                }
                case "VETOED" -> {
                    if (bridge != null) {
                        bridge.removeAnnouncement("appeal:" + p.id);
                        bridge.postAnnouncement("VETOED", ref, "【保民官否决】" + p.title,
                                "提案已被保民官否决；合资格元老可在 /eco gui 发起覆议。",
                                "元老院", 11, 0);
                    }
                    broadcastOfficial("保民官否决提案", NamedTextColor.RED, p,
                            "合资格元老可在元老院界面发起覆议。", "打开 /eco gui 发起覆议");
                }
                case "ABANDONED" -> {
                    clearStageAnnouncementState(p.id);
                    if (bridge != null) {
                        bridge.removeAnnouncement(ref);
                        bridge.removeAnnouncement("appeal:" + p.id);
                    }
                    broadcastOfficial("元老院覆议失败", NamedTextColor.RED, p,
                            resultLine(p) + " 未获全体一致赞成，提案已废弃。", "打开 /eco gui 查看结果");
                }
                default -> { }
            }
        } catch (Throwable error) {
            eco.getLogger().warning("[政治系统] 发布提案公告失败: " + error.getMessage());
        }
    }

    private void broadcastOfficial(String event, NamedTextColor eventColor, Proposal p,
                                   String detail, String actionLabel) {
        Component message = Component.text()
                .append(Component.text("[元老院] ", NamedTextColor.GOLD))
                .append(Component.text(event + ": ", eventColor))
                .append(Component.text(p.title, NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text("  " + detail, NamedTextColor.GRAY))
                .append(guiAction(actionLabel))
                .build();
        broadcast(message);
    }

    private Component guiAction(String label) {
        return Component.text("  [" + label + "]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/eco gui"));
    }

    /** 向全服在线玩家广播消息；Web 线程触发状态转换时切回服务器线程。 */
    private void broadcast(Component msg) {
        try {
            if (Bukkit.isPrimaryThread()) Bukkit.getServer().broadcast(msg);
            else Bukkit.getScheduler().runTask(eco, () -> Bukkit.getServer().broadcast(msg));
        } catch (Throwable error) {
            eco.getLogger().warning("[政治系统] 全服公告发送失败: " + error.getMessage());
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static String typeCn(String t) {
        if (t == null) return "决议";
        return switch (t) {
            case "SET_TAX_RATE" -> "设置税率";
            case "SET_TAX_BRACKET" -> "阶梯税率";
            case "SET_CB_RATES" -> "央行利率";
            case "CB_INJECT" -> "央行注资";
            case "SET_OFFICIAL_PRICE" -> "官方定价";
            case "RE_ZONE_ADMIN" -> "房地产管理";
            case "GENERAL" -> "一般决议";
            default -> t;
        };
    }

    private int votingDurationMinutes(String stage) {
        String key = "SENATE_OVERRIDE".equals(stage)
                ? "proposal_override_duration_minutes" : "proposal_vote_duration_minutes";
        int def = "SENATE_OVERRIDE".equals(stage) ? 720 : 1440;
        return Math.max(5, Math.min(10080, politicManager.getConfigInt(key, def)));
    }

    private void initializeMissingStageDeadlines() {
        long now = System.currentTimeMillis() / 1000;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            for (String stage : List.of("SENATE_VOTING", "SENATE_OVERRIDE")) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE ks_politic_proposals SET stage_started_at=?, stage_deadline_at=? " +
                                "WHERE status=? AND COALESCE(stage_deadline_at,0)<=0")) {
                    ps.setLong(1, now);
                    ps.setLong(2, now + votingDurationMinutes(stage) * 60L);
                    ps.setString(3, stage);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[政治系统] 初始化表决截止时间失败: " + e.getMessage());
        }
    }

    private void markStageAnnouncementStart(Proposal p) {
        String key = stageKey(p);
        lastAnnouncedVoteCount.put(key, 0);
        lastProgressAnnouncementAt.put(key, System.currentTimeMillis() / 1000);
        deadlineWarnings.remove(key);
    }

    private void clearStageAnnouncementState(String proposalId) {
        for (String stage : List.of("SENATE_VOTING", "SENATE_OVERRIDE")) {
            String key = proposalId + ":" + stage;
            lastAnnouncedVoteCount.remove(key);
            lastProgressAnnouncementAt.remove(key);
            deadlineWarnings.remove(key);
        }
    }

    private static String stageKey(Proposal p) { return p.id + ":" + p.status; }

    private static String resultLine(Proposal p) {
        return p.resultSummary != null && !p.resultSummary.isBlank() ? " ｜ " + p.resultSummary : "";
    }

    private static String formatDeadline(long epochSeconds) {
        if (epochSeconds <= 0) return "未设置";
        return new java.text.SimpleDateFormat("MM-dd HH:mm")
                .format(new java.util.Date(epochSeconds * 1000));
    }

    private static String formatRemaining(long seconds) {
        seconds = Math.max(0, seconds);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        if (hours > 0) return hours + "小时" + minutes + "分钟";
        return Math.max(1, minutes) + "分钟";
    }

    private String votingBulletinBody(Proposal p, VoteManager.Tally tally) {
        StringBuilder body = new StringBuilder();
        body.append("阶段: ").append("SENATE_OVERRIDE".equals(p.status) ? "元老院覆议" : "元老院表决")
                .append(" ｜ 类型: ").append(typeCn(p.proposalType))
                .append(" ｜ 截止: ").append(formatDeadline(p.stageDeadlineAt));
        if (tally != null) body.append("\n当前进展: ").append(tally.summary());
        if (p.description != null && !p.description.isBlank()) body.append("\n").append(p.description);
        body.append("\n进入 /eco gui → 元老院参与或查看。");
        return body.toString();
    }

    private void checkVotingAnnouncements() {
        try {
            long now = System.currentTimeMillis() / 1000;
            int progressInterval = Math.max(1,
                    politicManager.getConfigInt("proposal_progress_interval_minutes", 15)) * 60;
            int warningWindow = Math.max(1,
                    politicManager.getConfigInt("proposal_deadline_warning_minutes", 10)) * 60;
            for (Proposal proposal : listProposals(null, null, 200)) {
                if (!Set.of("SENATE_VOTING", "SENATE_OVERRIDE").contains(proposal.status)) continue;
                VoteManager.Tally tally = voteManager.countVotes(proposal.id, proposal.status);
                if (proposal.stageDeadlineAt > 0 && now >= proposal.stageDeadlineAt) {
                    String summary = tally.summary() + (tally.quorumMet() ? "" : "，截止时未达法定人数");
                    saveResultSummary(proposal.id, summary);
                    TransitionResult result = tally.quorumMet()
                            ? autoAdvanceAfterVote(proposal.id, tally)
                            : transitionProposal(proposal.id,
                                    "SENATE_OVERRIDE".equals(proposal.status) ? "ABANDONED" : "REJECTED",
                                    UUID.randomUUID(), "SYSTEM");
                    if (!result.success()) {
                        eco.getLogger().warning("[政治系统] 表决到期推进失败 " + proposal.id + ": " + result.error());
                    }
                    continue;
                }

                String key = stageKey(proposal);
                long remaining = proposal.stageDeadlineAt > 0 ? proposal.stageDeadlineAt - now : Long.MAX_VALUE;
                if (remaining <= warningWindow && deadlineWarnings.add(key)) {
                    announceVotingProgress(proposal, tally, true, remaining);
                    lastAnnouncedVoteCount.put(key, tally.totalVoted());
                    lastProgressAnnouncementAt.put(key, now);
                    continue;
                }

                int previousVotes = lastAnnouncedVoteCount.getOrDefault(key, 0);
                long previousAt = lastProgressAnnouncementAt.getOrDefault(key, 0L);
                if (tally.totalVoted() != previousVotes && now - previousAt >= progressInterval) {
                    announceVotingProgress(proposal, tally, false, remaining);
                    lastAnnouncedVoteCount.put(key, tally.totalVoted());
                    lastProgressAnnouncementAt.put(key, now);
                }
            }
        } catch (Throwable error) {
            eco.getLogger().warning("[政治系统] 表决公告巡检失败: " + error.getMessage());
        }
    }

    private void announceVotingProgress(Proposal p, VoteManager.Tally tally, boolean urgent, long remaining) {
        var bridge = eco.bridge();
        if (bridge != null) bridge.postAnnouncement("VOTING", "prop:" + p.id,
                (urgent ? "【即将截止】" : "【表决进展】") + p.title,
                votingBulletinBody(p, tally), "元老院", urgent ? 14 : 10, p.stageDeadlineAt);
        String detail = tally.summary() + " ｜ 距截止还有 " + formatRemaining(remaining);
        broadcastOfficial(urgent ? "表决即将截止" : "表决进展更新",
                urgent ? NamedTextColor.RED : NamedTextColor.YELLOW, p, detail,
                "打开 /eco gui 投票");
    }

    private void saveResultSummary(String proposalId, String summary) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_politic_proposals SET result_summary=? WHERE id=?")) {
                ps.setString(1, summary != null ? summary : "");
                ps.setString(2, proposalId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[政治系统] 保存表决结果摘要失败: " + e.getMessage());
        }
    }

    /** 合资格玩家针对表决中提案发起固定格式全服呼吁，带持久化多级冷却。 */
    public synchronized AppealResult appealForProposal(String proposalId, UUID actorUuid, String actorName) {
        if (proposalId == null || proposalId.isBlank() || actorUuid == null) return AppealResult.fail("提案信息缺失", 0);
        Player actor = Bukkit.getPlayer(actorUuid);
        if (actor == null) return AppealResult.fail("只有在线玩家可以发起全服呼吁", 0);
        if (!actor.hasPermission("kseco.politic.appeal") && !actor.hasPermission("kseco.admin")) {
            return AppealResult.fail("你没有发起全服呼吁的权限", 0);
        }

        Proposal proposal = getProposal(proposalId);
        if (proposal == null) return AppealResult.fail("提案不存在", 0);
        if (!Set.of("SENATE_VOTING", "SENATE_OVERRIDE", "TRIBUNE_REVIEW").contains(proposal.status)) {
            return AppealResult.fail("只有表决或审查中的提案可以发起呼吁", 0);
        }
        if (!isAppealEligible(proposal, actorUuid)) {
            return AppealResult.fail("只有提案人或当前阶段的合资格表决者可以发起呼吁", 0);
        }

        long now = System.currentTimeMillis() / 1000;
        int playerCooldown = Math.max(0, politicManager.getConfigInt("appeal_player_cooldown_seconds", 900));
        int proposalCooldown = Math.max(0, politicManager.getConfigInt("appeal_proposal_cooldown_seconds", 180));
        int globalCooldown = Math.max(0, politicManager.getConfigInt("appeal_global_cooldown_seconds", 60));
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return AppealResult.fail("数据库连接失败", 0);
            long lastPlayer = lastAppealAt(conn, "proposal_id=? AND actor_uuid=?", proposal.id, actorUuid.toString());
            long lastProposal = lastAppealAt(conn, "proposal_id=?", proposal.id);
            long lastGlobal = lastAppealAt(conn, "1=1");
            long remaining = Math.max(
                    Math.max(lastPlayer + playerCooldown - now, lastProposal + proposalCooldown - now),
                    lastGlobal + globalCooldown - now);
            if (remaining > 0) return AppealResult.fail("呼吁冷却中，还需等待 " + formatRemaining(remaining), remaining);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ks_politic_appeals " +
                            "(id, proposal_id, actor_uuid, actor_name, proposal_stage, appealed_at) VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, UUID.randomUUID().toString().substring(0, 8));
                ps.setString(2, proposal.id);
                ps.setString(3, actorUuid.toString());
                ps.setString(4, actorName != null ? actorName : actor.getName());
                ps.setString(5, proposal.status);
                ps.setLong(6, now);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[政治系统] 记录全服呼吁失败: " + e.getMessage());
            return AppealResult.fail("呼吁记录失败", 0);
        }

        VoteManager.Tally tally = voteManager.countVotes(proposal.id,
                "TRIBUNE_REVIEW".equals(proposal.status) ? "SENATE_VOTING" : proposal.status);
        String stage = switch (proposal.status) {
            case "SENATE_OVERRIDE" -> "元老院覆议";
            case "TRIBUNE_REVIEW" -> "保民官审查";
            default -> "元老院表决";
        };
        String caller = actorName != null && !actorName.isBlank() ? actorName : actor.getName();
        long expiresAt = now + Math.max(60,
                politicManager.getConfigInt("appeal_bulletin_duration_seconds", 900));
        var bridge = eco.bridge();
        String progress = "TRIBUNE_REVIEW".equals(proposal.status)
                ? "元老院结果: " + tally.summary() + " ｜ 当前等待保民官审查"
                : "当前进展: " + tally.summary();
        if (bridge != null) bridge.postAnnouncement("APPEAL", "appeal:" + proposal.id,
                "【城邦呼吁】关注《" + proposal.title + "》",
                caller + " 呼吁合资格人员参与" + stage + "。\n" + progress
                        + "\n进入 /eco gui → 元老院参与或查看。",
                caller, 13, expiresAt);
        broadcastOfficial(caller + " 发起城邦呼吁", NamedTextColor.AQUA, proposal,
                stage + "进行中 ｜ " + progress, "打开 /eco gui 响应呼吁");
        eco.getLogger().info("[政治系统] " + caller + " 针对提案 " + proposal.id + " 发起全服呼吁");
        return AppealResult.success("全服呼吁已发布");
    }

    private boolean isAppealEligible(Proposal p, UUID actorUuid) {
        if (p.proposerUuid.equals(actorUuid.toString())) return true;
        return switch (p.status) {
            case "SENATE_VOTING", "SENATE_OVERRIDE" -> politicManager.canVoteInSenate(actorUuid);
            case "TRIBUNE_REVIEW" -> politicManager.canVeto(actorUuid);
            default -> false;
        };
    }

    private long lastAppealAt(Connection conn, String where, String... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(MAX(appealed_at),0) FROM ks_politic_appeals WHERE " + where)) {
            for (int i = 0; i < params.length; i++) ps.setString(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        }
    }

    /**
     * 计票后自动推进状态机。
     *
     * 当计票满足 quorum 时调用此方法，自动进行状态转换。
     */
    public TransitionResult autoAdvanceAfterVote(String proposalId, VoteManager.Tally tally) {
        Proposal p = getProposal(proposalId);
        if (p == null) return TransitionResult.fail("提案不存在");

        String nextState;
        switch (p.status) {
            case "SENATE_VOTING":
                nextState = tally.passed() ? "TRIBUNE_REVIEW" : "REJECTED";
                p.resultSummary = tally.summary();
                saveResultSummary(proposalId, p.resultSummary);
                break;

            case "SENATE_OVERRIDE":
                nextState = tally.unanimous() ? "OVERRIDDEN" : "ABANDONED";
                p.resultSummary = tally.summary();
                saveResultSummary(proposalId, p.resultSummary);
                break;

            case "APPROVED":
            case "OVERRIDDEN":
                nextState = "ENACTED";
                break;

            default:
                return TransitionResult.fail("当前状态 " + p.status + " 不支持计票自动推进");
        }

        TransitionResult result = transitionProposal(proposalId, nextState, UUID.randomUUID(), "SYSTEM");
        // 覆议全票通过 → OVERRIDDEN，需继续自动颁布为 ENACTED（此前缺这一跳，覆议永远停在 OVERRIDDEN）
        if (result.success() && "OVERRIDDEN".equals(nextState)) {
            return transitionProposal(proposalId, "ENACTED", UUID.randomUUID(), "SYSTEM");
        }
        return result;
    }

    // ================================================================
    // 法案执行（ENACTED 时触发）
    // ================================================================

    /**
     * 执行已通过法案的 payload。
     * 直接写入对应的业务表，绕过 Web API。
     *
     * @return null 表示执行成功；非 null 为错误信息（调用方据此拒绝颁布）。
     *         未知类型（如 GENERAL 宣示性提案）视为成功——它们没有自动副作用。
     */
    private String enact(Proposal p) {
        String validationError = validateProposalPayload(p.proposalType, p.payload);
        if (validationError != null) return validationError;
        long now = System.currentTimeMillis() / 1000;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return "数据库连接失败";

            switch (p.proposalType) {
                case "SET_TAX_RATE" -> {
                    // payload: {category, rate, industry?}
                    // 写入 ks_tax_rates
                    try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ks_tax_rates (category, industry, rate, updated_at) VALUES (?,?,?,?) " +
                        "ON CONFLICT(category, industry) DO UPDATE SET rate=excluded.rate, updated_at=excluded.updated_at")) {
                        ps.setString(1, p.payload.getOrDefault("category", "").toString());
                        String ind = p.payload.get("industry") != null ? p.payload.get("industry").toString() : null;
                        if (ind == null || ind.isEmpty()) ps.setNull(2, Types.VARCHAR);
                        else ps.setString(2, ind);
                        ps.setDouble(3, Double.parseDouble(p.payload.getOrDefault("rate", "0.08").toString()));
                        ps.setLong(4, now);
                        ps.executeUpdate();
                    }
                    eco.getLogger().info("[政治系统] 法案执行: 设置税率 " + p.payload);
                }

                case "SET_TAX_BRACKET" -> {
                    // payload: {id, industry, scope, profitMin, profitMax, rate, action:upsert|delete}
                    String action = (String) p.payload.getOrDefault("action", "upsert");
                    if ("delete".equals(action)) {
                        try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM ks_tax_brackets WHERE id=?")) {
                            ps.setString(1, (String) p.payload.get("id"));
                            ps.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO ks_tax_brackets (id, industry, scope, profit_min, profit_max, rate, updated_at) " +
                            "VALUES (?,?,?,?,?,?,?) " +
                            "ON CONFLICT(id) DO UPDATE SET industry=excluded.industry, profit_min=excluded.profit_min, " +
                            "profit_max=excluded.profit_max, rate=excluded.rate, updated_at=excluded.updated_at")) {
                            ps.setString(1, (String) p.payload.getOrDefault("id", UUID.randomUUID().toString().substring(0, 8)));
                            ps.setString(2, (String) p.payload.getOrDefault("industry", "OTHER"));
                            ps.setString(3, (String) p.payload.getOrDefault("scope", "ENTERPRISE_TAX"));
                            ps.setDouble(4, Double.parseDouble(p.payload.getOrDefault("profitMin", "0").toString()));
                            ps.setDouble(5, Double.parseDouble(p.payload.getOrDefault("profitMax", "1000000").toString()));
                            ps.setDouble(6, Double.parseDouble(p.payload.getOrDefault("rate", "0.05").toString()));
                            ps.setLong(7, now);
                            ps.executeUpdate();
                        }
                    }
                    eco.getLogger().info("[政治系统] 法案执行: 设置阶梯税率 " + p.payload);
                }

                case "SET_CB_RATES" -> {
                    // payload: {baseRate, reserveRequirement}
                    double baseRate = Double.parseDouble(p.payload.getOrDefault("baseRate", "0.03").toString());
                    double reserveReq = Double.parseDouble(p.payload.getOrDefault("reserveRequirement", "0.10").toString());
                    try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ks_bank_cb_rates (base_rate, reserve_requirement, set_at) VALUES (?,?,?)")) {
                        ps.setDouble(1, baseRate);
                        ps.setDouble(2, reserveReq);
                        ps.setLong(3, now);
                        ps.executeUpdate();
                    }
                    // 同时写 cb_config
                    try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR REPLACE INTO ks_bank_cb_config (key, value) VALUES ('base_rate',?), ('reserve_requirement',?)")) {
                        ps.setString(1, String.valueOf(baseRate));
                        ps.setString(2, String.valueOf(reserveReq));
                        ps.executeUpdate();
                    }
                    eco.getLogger().info("[政治系统] 法案执行: 设置央行利率");
                }

                case "CB_INJECT" -> {
                    // payload: {bankId, amount, mode:GRANT|LOAN}
                    double amount = Double.parseDouble(p.payload.getOrDefault("amount", "0").toString());
                    String bankId = (String) p.payload.get("bankId");
                    String mode = (String) p.payload.getOrDefault("mode", "GRANT");
                    if ("LOAN".equals(mode)) {
                        // 直接写入 LOAN 方式（简单版：只记入 cb_loans）
                        String loanId = UUID.randomUUID().toString().substring(0, 8);
                        double rate = Double.parseDouble(p.payload.getOrDefault("interestRate", "0.05").toString());
                        int termDays = Integer.parseInt(p.payload.getOrDefault("termDays", "30").toString());
                        long dueAt = now + (termDays * 86400L);
                        try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO ks_bank_cb_loans (id, bank_id, principal, interest_rate, term_days, issued_at, due_at, repaid) " +
                            "VALUES (?,?,?,?,?,?,?,0)")) {
                            ps.setString(1, loanId);
                            ps.setString(2, bankId);
                            ps.setDouble(3, amount);
                            ps.setDouble(4, rate);
                            ps.setInt(5, termDays);
                            ps.setLong(6, now);
                            ps.setLong(7, dueAt);
                            ps.executeUpdate();
                        }
                    }
                    // GRANT: 直接加总资产
                    try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE ks_bank_banks SET total_assets=total_assets+? WHERE id=?")) {
                        ps.setDouble(1, amount);
                        ps.setString(2, bankId);
                        ps.executeUpdate();
                    }
                    eco.getLogger().info("[政治系统] 法案执行: 央行注资 " + amount + " to " + bankId + " mode=" + mode);
                }

                case "SET_OFFICIAL_PRICE" -> {
                    // payload: {prices: [{material, buyPrice, category}, ...]}
                    // 官方只收购、不直售（直售已由盲盒系统取代），因此这里不再写入 sellPrice。
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> prices = (List<Map<String, Object>>) p.payload.getOrDefault("prices", List.of());
                    for (Map<String, Object> price : prices) {
                        String material = (String) price.get("material");
                        double buyPrice = Double.parseDouble(price.getOrDefault("buyPrice", "0").toString());
                        try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO ks_official_prices (material, buy_price, category, updated_at) " +
                            "VALUES (?,?,?,?) " +
                            "ON CONFLICT(material) DO UPDATE SET buy_price=excluded.buy_price, " +
                            "category=excluded.category, updated_at=excluded.updated_at")) {
                            ps.setString(1, material);
                            ps.setDouble(2, buyPrice);
                            ps.setString(3, (String) price.getOrDefault("category", ""));
                            ps.setLong(4, now);
                            ps.executeUpdate();
                        }
                        // ★ 同步到价格引擎内存缓存，立即生效
                        eco.priceEngine().registerItem(material, buyPrice);
                    }
                    eco.getLogger().info("[政治系统] 法案执行: 设置官价，共 " + prices.size() + " 条");
                }

                case "RE_ZONE_ADMIN" -> {
                    // payload: {zoneId, action:create|setPrice|setStatus, price?, status?}
                    String action = (String) p.payload.getOrDefault("action", "create");
                    if ("create".equals(action)) {
                        String zoneId = UUID.randomUUID().toString().substring(0, 8);
                        try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO ks_re_zones (id, name, world, x1, z1, x2, z2, type, base_price, status) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                            ps.setString(1, zoneId);
                            ps.setString(2, (String) p.payload.getOrDefault("name", "未命名区域"));
                            ps.setString(3, (String) p.payload.getOrDefault("world", "world"));
                            ps.setInt(4, Integer.parseInt(p.payload.getOrDefault("x1", "0").toString()));
                            ps.setInt(5, Integer.parseInt(p.payload.getOrDefault("z1", "0").toString()));
                            ps.setInt(6, Integer.parseInt(p.payload.getOrDefault("x2", "0").toString()));
                            ps.setInt(7, Integer.parseInt(p.payload.getOrDefault("z2", "0").toString()));
                            ps.setString(8, (String) p.payload.getOrDefault("type", "RESIDENTIAL"));
                            ps.setDouble(9, Double.parseDouble(p.payload.getOrDefault("basePrice", "0").toString()));
                            ps.setString(10, (String) p.payload.getOrDefault("status", "FOR_SALE"));
                            ps.executeUpdate();
                        }
                    } else if ("setPrice".equals(action)) {
                        try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE ks_re_zones SET base_price=? WHERE id=?")) {
                            ps.setDouble(1, Double.parseDouble(p.payload.getOrDefault("price", "0").toString()));
                            ps.setString(2, (String) p.payload.get("zoneId"));
                            ps.executeUpdate();
                        }
                    } else if ("setStatus".equals(action)) {
                        try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE ks_re_zones SET status=? WHERE id=?")) {
                            ps.setString(1, (String) p.payload.getOrDefault("status", "FOR_SALE"));
                            ps.setString(2, (String) p.payload.get("zoneId"));
                            ps.executeUpdate();
                        }
                    }
                    eco.getLogger().info("[政治系统] 法案执行: 房地产管理 " + p.payload);
                }

                default -> eco.getLogger().warning("[政治系统] 未知提案类型: " + p.proposalType);
            }
        } catch (SQLException | NumberFormatException e) {
            eco.getLogger().warning("[政治系统] 法案执行失败: " + e.getMessage());
            return e.getMessage();
        }
        return null;
    }

    public static String validateProposalPayload(String proposalType, Map<String, Object> payload) {
        if (proposalType == null || payload == null) return "提案类型或参数缺失";
        try {
            return switch (proposalType) {
                case "GENERAL" -> null;
                case "SET_TAX_RATE" -> {
                    String category = text(payload.get("category"));
                    double rate = number(payload.get("rate"));
                    yield category.isBlank() || !between(rate, 0, 1) ? "税率提案参数无效" : null;
                }
                case "SET_TAX_BRACKET" -> {
                    String action = text(payload.getOrDefault("action", "upsert"));
                    if ("delete".equals(action)) yield text(payload.get("id")).isBlank() ? "删除阶梯税率必须指定 ID" : null;
                    if (!"upsert".equals(action)) yield "阶梯税率操作无效";
                    double min = number(payload.get("profitMin"));
                    double max = number(payload.get("profitMax"));
                    double rate = number(payload.get("rate"));
                    String scope = text(payload.get("scope"));
                    yield !Double.isFinite(min) || !Double.isFinite(max) || min < 0 || max < min
                            || !between(rate, 0, 1) || !Set.of("ENTERPRISE_TAX", "PERSONAL_TAX").contains(scope)
                            ? "阶梯税率参数无效" : null;
                }
                case "SET_CB_RATES" -> between(number(payload.get("baseRate")), 0, 1)
                        && between(number(payload.get("reserveRequirement")), 0, 1) ? null : "央行利率参数无效";
                case "CB_INJECT" -> {
                    String bankId = text(payload.get("bankId"));
                    String mode = text(payload.getOrDefault("mode", "GRANT"));
                    double amount = number(payload.get("amount"));
                    if (bankId.isBlank() || !between(amount, 0.01, 1_000_000_000_000d)
                            || !Set.of("GRANT", "LOAN").contains(mode)) yield "央行注资参数无效";
                    if ("LOAN".equals(mode) && (!between(number(payload.get("interestRate")), 0, 1)
                            || !between(number(payload.get("termDays")), 1, 3650))) yield "央行贷款参数无效";
                    yield null;
                }
                case "SET_OFFICIAL_PRICE" -> {
                    Object raw = payload.get("prices");
                    if (!(raw instanceof List<?> prices) || prices.isEmpty() || prices.size() > 1000) yield "官方定价列表无效";
                    String error = null;
                    for (Object value : prices) {
                        if (!(value instanceof Map<?, ?> price)
                                || !text(price.get("material")).matches("[A-Z0-9_]{1,64}")
                                || !between(number(price.get("buyPrice")), 0, 1_000_000_000_000d)) {
                            error = "官方定价条目无效";
                            break;
                        }
                    }
                    yield error;
                }
                case "RE_ZONE_ADMIN" -> {
                    String action = text(payload.getOrDefault("action", "create"));
                    if ("setPrice".equals(action)) yield text(payload.get("zoneId")).isBlank()
                            || !between(number(payload.get("price")), 0, 1_000_000_000_000d) ? "区域价格参数无效" : null;
                    if ("setStatus".equals(action)) yield text(payload.get("zoneId")).isBlank()
                            || !Set.of("FOR_SALE", "STATE_OWNED", "SOLD").contains(text(payload.get("status"))) ? "区域状态参数无效" : null;
                    if (!"create".equals(action)) yield "房地产提案操作无效";
                    String name = text(payload.get("name"));
                    String world = text(payload.get("world"));
                    String type = text(payload.get("type"));
                    double x1 = number(payload.get("x1")), z1 = number(payload.get("z1"));
                    double x2 = number(payload.get("x2")), z2 = number(payload.get("z2"));
                    double price = number(payload.get("basePrice"));
                    yield name.isBlank() || name.length() > 64 || world.isBlank() || world.length() > 64
                            || !Set.of("RESIDENTIAL", "COMMERCIAL", "INDUSTRIAL", "AGRICULTURAL").contains(type)
                            || !Double.isFinite(x1) || !Double.isFinite(z1) || !Double.isFinite(x2) || !Double.isFinite(z2)
                            || x1 == x2 || z1 == z2 || !between(price, 0, 1_000_000_000_000d)
                            ? "房地产区域参数无效" : null;
                }
                default -> "不支持的提案类型";
            };
        } catch (RuntimeException e) {
            return "提案参数格式无效";
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble(text(value));
    }

    private static boolean between(double value, double min, double max) {
        return Double.isFinite(value) && value >= min && value <= max;
    }

    // ================================================================
    // 数据类
    // ================================================================

    public static class Proposal {
        public final String id;
        public final String title;
        public final String description;
        public final String proposalType;
        public final String targetEndpoint;
        public final Map<String, Object> payload; // parsed from payload_json
        public final String proposerUuid;
        public final String proposerName;
        public final String proposerOffice;
        public String status;
        public String resultSummary;
        public final long createdAt;
        public long enactedAt;
        public long stageStartedAt;
        public long stageDeadlineAt;

        @SuppressWarnings("unchecked")
        Proposal(ResultSet rs) throws SQLException {
            this.id = rs.getString("id");
            this.title = rs.getString("title");
            this.description = rs.getString("description");
            this.proposalType = rs.getString("proposal_type");
            this.targetEndpoint = rs.getString("target_endpoint");
            String pj = rs.getString("payload_json");
            Map<String, Object> parsed = new LinkedHashMap<>();
            if (pj != null && !pj.isEmpty() && !"{}".equals(pj)) {
                try { parsed = new com.google.gson.Gson().fromJson(pj, Map.class); }
                catch (Exception ignored) {}
            }
            this.payload = parsed != null ? parsed : Map.of();
            this.proposerUuid = rs.getString("proposer_uuid");
            this.proposerName = rs.getString("proposer_name");
            this.proposerOffice = rs.getString("proposer_office");
            this.status = rs.getString("status");
            this.resultSummary = rs.getString("result_summary");
            this.createdAt = rs.getLong("created_at");
            this.enactedAt = rs.getLong("enacted_at");
            this.stageStartedAt = rs.getLong("stage_started_at");
            this.stageDeadlineAt = rs.getLong("stage_deadline_at");
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("title", title);
            m.put("description", description);
            m.put("proposalType", proposalType);
            m.put("targetEndpoint", targetEndpoint);
            m.put("payload", payload);
            m.put("proposerUuid", proposerUuid);
            m.put("proposerName", proposerName);
            m.put("proposerOffice", proposerOffice);
            m.put("status", status);
            m.put("resultSummary", resultSummary != null ? resultSummary : "");
            m.put("createdAt", createdAt);
            m.put("enactedAt", enactedAt);
            m.put("stageStartedAt", stageStartedAt);
            m.put("stageDeadlineAt", stageDeadlineAt);
            return m;
        }
    }

    public record CreateResult(boolean success, String id, String title, String error) {
        public static CreateResult success(String id, String title) { return new CreateResult(true, id, title, null); }
        public static CreateResult fail(String err) { return new CreateResult(false, null, null, err); }
    }

    public record TransitionResult(boolean success, Proposal proposal, String fromState,
                                    String toState, String error) {
        public static TransitionResult success(Proposal p, String from, String to) {
            return new TransitionResult(true, p, from, to, null);
        }
        public static TransitionResult fail(String err) {
            return new TransitionResult(false, null, null, null, err);
        }
    }

    public record AppealResult(boolean success, String message, String error, long remainingSeconds) {
        public static AppealResult success(String message) {
            return new AppealResult(true, message, null, 0);
        }
        public static AppealResult fail(String error, long remainingSeconds) {
            return new AppealResult(false, null, error, Math.max(0, remainingSeconds));
        }
    }
}
