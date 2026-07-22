package org.kseco.extra.politic;

import org.kseco.KsEco;
import org.kseco.database.PortableSqlMutation;

import java.sql.*;
import java.util.*;

/**
 * 投票管理器。
 *
 * 负责投票记录、计票、法定人数判定，以及元老院覆议的全票一致判定。
 * 这是"二次全票判定"的底层实现。
 */
public final class VoteManager {

    private static final Set<String> VOTE_STAGES = Set.of(
            "SENATE_VOTING", "TRIBUNE_REVIEW", "SENATE_OVERRIDE");

    private final KsEco eco;
    private final PoliticManager politicManager;
    private volatile boolean electorateStoreReady;

    public VoteManager(KsEco eco, PoliticManager politicManager) {
        this.eco = eco;
        this.politicManager = politicManager;
    }

    public void init() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            ProposalElectorateStore.ensureSchema(conn);
            electorateStoreReady = true;
            initializeMissingElectorates(conn);
        } catch (SQLException e) {
            electorateStoreReady = false;
            eco.getLogger().severe("[Politic] Electorate snapshot initialization failed: " + e.getMessage());
        }
    }

    private void initializeMissingElectorates(Connection conn) throws SQLException {
        List<StageRef> activeStages = new ArrayList<>();
        try (PreparedStatement select = conn.prepareStatement("""
                SELECT id,status FROM ks_politic_proposals
                WHERE status IN ('SENATE_VOTING','TRIBUNE_REVIEW','SENATE_OVERRIDE')
                """)) {
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) activeStages.add(new StageRef(rs.getString(1), rs.getString(2)));
            }
        }
        if (activeStages.isEmpty()) return;

        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            long now = System.currentTimeMillis() / 1000;
            for (StageRef ref : activeStages) {
                if (!ProposalElectorateStore.loadSnapshot(conn, ref.proposalId(), ref.stage()).exists()) {
                    ProposalElectorateStore.replaceSnapshot(
                            conn, ref.proposalId(), ref.stage(), currentElectors(ref.stage()), now);
                }
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    void snapshotElectorate(Connection conn, String proposalId, String stage, long now) throws SQLException {
        if (!electorateStoreReady) throw new SQLException("Electorate snapshot store is unavailable");
        ProposalElectorateStore.replaceSnapshot(
                conn, proposalId, stage, currentElectors(stage), now);
    }

    private List<ProposalElectorateStore.Elector> currentElectors(String stage) {
        LinkedHashMap<String, String> electors = new LinkedHashMap<>();
        if ("TRIBUNE_REVIEW".equals(stage)) {
            for (PoliticManager.Office office : politicManager.getTribunes()) {
                electors.put(office.playerUuid, "TRIBUNE");
            }
        } else {
            for (PoliticManager.Office office : politicManager.getSenators()) {
                electors.put(office.playerUuid, "SENATOR");
            }
            PoliticManager.Office consul = politicManager.getConsul();
            if (consul != null) electors.put(consul.playerUuid, "CONSUL");
        }
        List<ProposalElectorateStore.Elector> snapshot = new ArrayList<>();
        electors.forEach((uuid, office) -> snapshot.add(
                new ProposalElectorateStore.Elector(uuid, office)));
        return snapshot;
    }

    // ================================================================
    // 投票
    // ================================================================

    /**
     * 投出表决票。
     *
     * @param proposalId 提案 ID
     * @param voterUuid  投票人 UUID
     * @param voterName  投票人名称
     * @param voterOffice 投票人的职务（SENATOR/CONSUL/TRIBUNE）
     * @param vote       YES / NO / ABSTAIN
     * @param stage      当前投票阶段（SENATE_VOTING / TRIBUNE_REVIEW / SENATE_OVERRIDE）
     * @return VoteResult
     */
    public VoteResult castVote(String proposalId, UUID voterUuid, String voterName,
                                String voterOffice, String vote, String stage) {
        if (proposalId == null || proposalId.isBlank() || voterUuid == null ||
                voterOffice == null || voterOffice.isBlank() || stage == null || stage.isBlank()) {
            return VoteResult.fail("投票信息不完整");
        }
        if (!VOTE_STAGES.contains(stage)) return VoteResult.fail("无效投票阶段: " + stage);
        if (!isValidVote(vote))
            return VoteResult.fail("无效投票值: " + vote + "（仅支持 YES/NO/ABSTAIN）");
        if (!getEligibleVoters(proposalId, stage).contains(voterUuid.toString())) {
            return VoteResult.fail("你没有当前阶段的投票资格");
        }

        String candidateVoteId = UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;
        String voteId;
        String recordedOffice = "TRIBUNE_REVIEW".equals(stage)
                ? "TRIBUNE"
                : (politicManager.isConsul(voterUuid) ? "CONSUL" : "SENATOR");

        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return VoteResult.fail("数据库连接失败");

            PortableSqlMutation.upsert(conn,
                    "UPDATE ks_politic_votes SET voter_name=?,voter_office=?,vote=?,cast_at=? " +
                            "WHERE proposal_id=? AND voter_uuid=? AND vote_stage=? " +
                            "AND EXISTS (SELECT 1 FROM ks_politic_proposals WHERE id=? AND status=?)",
                    ps -> { ps.setString(1, voterName != null ? voterName : ""); ps.setString(2, recordedOffice);
                        ps.setString(3, vote); ps.setLong(4, now); ps.setString(5, proposalId);
                        ps.setString(6, voterUuid.toString()); ps.setString(7, stage);
                        ps.setString(8, proposalId); ps.setString(9, stage); },
                    "INSERT INTO ks_politic_votes (id,proposal_id,voter_uuid,voter_name,voter_office,vote,vote_stage,cast_at) " +
                            "SELECT ?,?,?,?,?,?,?,? FROM ks_politic_proposals WHERE id=? AND status=?",
                    ps -> { ps.setString(1, candidateVoteId); ps.setString(2, proposalId);
                        ps.setString(3, voterUuid.toString()); ps.setString(4, voterName != null ? voterName : "");
                        ps.setString(5, recordedOffice); ps.setString(6, vote); ps.setString(7, stage);
                        ps.setLong(8, now); ps.setString(9, proposalId); ps.setString(10, stage); });

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM ks_politic_votes " +
                    "WHERE proposal_id=? AND voter_uuid=? AND vote_stage=?")) {
                ps.setString(1, proposalId);
                ps.setString(2, voterUuid.toString());
                ps.setString(3, stage);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return VoteResult.fail("投票记录写入失败");
                    voteId = rs.getString("id");
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[政治系统] 投票失败: " + e.getMessage());
            return VoteResult.fail("数据库写入失败");
        }

        // 投票后立刻计票，判断是否触发状态流转
        Tally tally = countVotes(proposalId, stage);
        return VoteResult.success(voteId, tally);
    }

    private boolean isValidVote(String vote) {
        return "YES".equals(vote) || "NO".equals(vote) || "ABSTAIN".equals(vote);
    }

    // ================================================================
    // 计票
    // ================================================================

    /**
     * 针对指定提案+阶段计票。
     *
     * 不同阶段有不同法定人数规则：
     * - SENATE_VOTING: only when remaining ballots cannot reverse an absolute majority
     * - TRIBUNE_REVIEW: 任一保民官投票即决定
     * - SENATE_OVERRIDE: 全体元老必须全投 YES（0 弃权 0 反对）= 全票一致
     */
    public Tally countVotes(String proposalId, String stage) {
        List<VoteRecord> votes = new ArrayList<>();
        int snapshotEligibleCount;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return Tally.error("数据库连接失败");

            Set<String> eligible = new LinkedHashSet<>(eligibleVoters(conn, proposalId, stage));
            snapshotEligibleCount = eligible.size();

            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT voter_uuid, voter_name, voter_office, vote FROM ks_politic_votes " +
                "WHERE proposal_id=? AND vote_stage=? ORDER BY cast_at")) {
                ps.setString(1, proposalId);
                ps.setString(2, stage);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (!eligible.contains(rs.getString("voter_uuid"))) continue;
                        votes.add(new VoteRecord(
                            rs.getString("voter_uuid"),
                            rs.getString("voter_name"),
                            rs.getString("voter_office"),
                            rs.getString("vote")));
                    }
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[政治系统] 计票失败: " + e.getMessage());
            return Tally.error("计票 SQL 异常");
        }

        int yes = 0, no = 0, abstain = 0;
        for (VoteRecord v : votes) {
            switch (v.vote) {
                case "YES": yes++; break;
                case "NO": no++; break;
                default: abstain++; break;
            }
        }

        int totalVoted = votes.size();

        if ("TRIBUNE_REVIEW".equals(stage)) {
            // 保民官审阅：任一保民官投票即决定
            return new Tally(stage, 1, totalVoted, yes, no, abstain,
                totalVoted >= 1, yes > 0, false,
                yes + " 赞成 / " + no + " 反对，保民官审查阶段");
        }

        // 元老院投票
        int totalEligible = snapshotEligibleCount;
        boolean quorumMet;
        boolean passed;
        boolean unanimous = false;

        if ("SENATE_OVERRIDE".equals(stage)) {
            // 全票一致：ALL eligible must vote YES, 0 ABSTAIN, 0 NO
            quorumMet = totalEligible > 0 && (totalVoted == totalEligible);
            unanimous = quorumMet && (yes == totalEligible && no == 0 && abstain == 0);
            passed = unanimous;
        } else {
            // SENATE_VOTING: only finalize once remaining ballots cannot reverse the majority.
            // majorityThreshold is the absolute YES majority among all eligible seats.
            int majorityThreshold = (totalEligible / 2) + 1;  // floor(N/2)+1
            int remaining = Math.max(0, totalEligible - totalVoted);
            boolean irreversiblePass = totalEligible > 0 && yes >= majorityThreshold;
            boolean irreversibleFail = totalEligible > 0
                    && (yes + remaining) < majorityThreshold;
            quorumMet = irreversiblePass || irreversibleFail;
            passed = irreversiblePass;
        }

        String summary = yes + " 赞成 / " + no + " 反对 / " + abstain + " 弃权" +
            "（共 " + totalEligible + " 席, " + totalVoted + " 已投票）";
        if (unanimous) summary += " — 🏛 全票一致通过！";

        return new Tally(stage, totalEligible, totalVoted, yes, no, abstain,
            quorumMet, passed, unanimous, summary);
    }

    /**
     * 获取指定阶段的合格投票人数。
     */
    public int getEligibleVoterCount(String stage) {
        return getEligibleVoters(stage).size();
    }

    public int getEligibleVoterCount(String proposalId, String stage) {
        return getEligibleVoters(proposalId, stage).size();
    }

    /**
     * 获取合格投票人的 UUID 列表（按 UUID 去重）。
     *
     * 注意：执政官同时持有 SENATOR 与 CONSUL 两条职务记录，
     * 因此 getSenators() 已包含执政官，必须去重避免重复计票
     * （否则 SENATE_OVERRIDE 全票判定 totalVoted==totalEligible 永远无法满足）。
     */
    public List<String> getEligibleVoters(String stage) {
        List<String> uuids = new ArrayList<>();
        for (ProposalElectorateStore.Elector elector : currentElectors(stage)) {
            uuids.add(elector.voterUuid());
        }
        return uuids;
    }

    public List<String> getEligibleVoters(String proposalId, String stage) {
        if (!electorateStoreReady) return List.of();
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return List.of();
            return eligibleVoters(conn, proposalId, stage);
        } catch (SQLException e) {
            eco.getLogger().warning("[Politic] Failed to load electorate snapshot for "
                    + proposalId + ": " + e.getMessage());
            return List.of();
        }
    }

    private List<String> eligibleVoters(Connection conn, String proposalId, String stage) throws SQLException {
        ProposalElectorateStore.Snapshot snapshot =
                ProposalElectorateStore.loadSnapshot(conn, proposalId, stage);
        if (!snapshot.exists()) return List.of();
        List<String> uuids = new ArrayList<>();
        for (ProposalElectorateStore.Elector elector : snapshot.electors()) {
            uuids.add(elector.voterUuid());
        }
        return uuids;
    }

    /**
     * 获取指定提案+阶段的投票明细。
     */
    public List<Map<String, Object>> getVoteDetails(String proposalId, String stage) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return List.of();
            return ProposalVoteStore.readStageVotes(conn, proposalId, stage);
        } catch (SQLException e) {
            eco.getLogger().warning("[政治系统] 读取投票明细失败: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * 获取指定阶段未投票的合格人列表。
     */
    public List<String> getNonVoters(String proposalId, String stage) {
        List<String> eligible = getEligibleVoters(proposalId, stage);
        List<String> voted = new ArrayList<>();
        List<Map<String, Object>> details = getVoteDetails(proposalId, stage);
        for (Map<String, Object> d : details) voted.add((String) d.get("voterUuid"));
        eligible.removeAll(voted);
        return eligible;
    }

    /**
     * 清除指定提案+阶段的投票记录（覆议开始时用）。
     */
    public boolean clearStageVotes(String proposalId, String stage) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM ks_politic_votes WHERE proposal_id=? AND vote_stage=?")) {
                ps.setString(1, proposalId);
                ps.setString(2, stage);
                ps.executeUpdate();
                return true;
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[政治系统] 清除投票失败: " + e.getMessage());
            return false;
        }
    }

    // ================================================================
    // 获取玩家投票记录
    // ================================================================

    public List<Map<String, Object>> getPlayerVotes(UUID playerUuid) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return List.of();
            return ProposalVoteStore.readPlayerVotes(conn, playerUuid);
        } catch (SQLException e) {
            eco.getLogger().warning("[政治系统] 读取投票记录失败: " + e.getMessage());
            return List.of();
        }
    }

    // ================================================================
    // 数据类
    // ================================================================

    public record Tally(String stage, int totalEligible, int totalVoted,
                         int yesCount, int noCount, int abstainCount,
                         boolean quorumMet, boolean passed, boolean unanimous,
                         String summary) {
        public static Tally error(String msg) {
            return new Tally("", 0, 0, 0, 0, 0, false, false, false, msg);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stage", stage);
            m.put("totalEligible", totalEligible);
            m.put("totalVoted", totalVoted);
            m.put("yesCount", yesCount);
            m.put("noCount", noCount);
            m.put("abstainCount", abstainCount);
            m.put("quorumMet", quorumMet);
            m.put("passed", passed);
            m.put("unanimous", unanimous);
            m.put("summary", summary);
            return m;
        }
    }

    public record VoteResult(boolean success, String voteId, Tally tally, String error) {
        public static VoteResult success(String id, Tally t) { return new VoteResult(true, id, t, null); }
        public static VoteResult fail(String err) { return new VoteResult(false, null, null, err); }
    }

    private record StageRef(String proposalId, String stage) {}

    private record VoteRecord(String voterUuid, String voterName, String voterOffice, String vote) {}
}
