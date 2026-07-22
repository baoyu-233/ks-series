package org.kseco.extra.bank;

import org.kseco.KsEco;

import java.sql.*;
import java.util.*;

/**
 * 央行有息借贷管理。
 *
 * 表：ks_bank_cb_loans
 *   id, bank_id, principal, interest_rate, term_days, issued_at, due_at, repaid
 *
 * 用法：
 * - 央行注资 LOAN 模式写入一条记录
 * - 到期（due_at < now 且未还）时自动从银行总资产扣除 (principal + interest)
 * - admin 可手动还款
 */
public final class CbLoanManager {

    private final KsEco eco;

    public CbLoanManager(KsEco eco) {
        this.eco = eco;
    }

    public void init() {
        ensureTable();
    }

    private void ensureTable() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            CentralBankLoanSchema.initialize(conn);
        } catch (SQLException e) {
            eco.getLogger().warning("[央行贷款] 建表失败: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> listLoans(String bankId, boolean includeRepaid) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;
            // WHERE 子句按条件拼装——旧写法 bankId=null 且过滤已还款时会拼出
            // "FROM ks_bank_cb_loans AND repaid=0" 的非法 SQL
            List<String> conds = new ArrayList<>();
            if (bankId != null) conds.add("bank_id=?");
            if (!includeRepaid) conds.add("repaid=0");
            String sql = "SELECT * FROM ks_bank_cb_loans" +
                    (conds.isEmpty() ? "" : " WHERE " + String.join(" AND ", conds)) +
                    " ORDER BY issued_at DESC LIMIT 100";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (bankId != null) ps.setString(1, bankId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getString("id"));
                        m.put("bankId", rs.getString("bank_id"));
                        m.put("principal", rs.getDouble("principal"));
                        m.put("interestRate", rs.getDouble("interest_rate"));
                        m.put("termDays", rs.getInt("term_days"));
                        m.put("issuedAt", rs.getLong("issued_at"));
                        m.put("dueAt", rs.getLong("due_at"));
                        m.put("repaid", rs.getInt("repaid") == 1);
                        m.put("outstanding", outstandingAmount(rs.getDouble("principal"),
                                rs.getDouble("interest_rate"), rs.getInt("term_days"),
                                rs.getLong("issued_at"), rs.getLong("due_at"),
                                System.currentTimeMillis() / 1000));
                        out.add(m);
                    }
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("查央行贷款失败: " + e.getMessage());
        }
        return out;
    }

    /** 简单按整期一次性计息（不按日） */
    private double outstandingAmount(double principal, double rate, int termDays,
                                      long issuedAt, long dueAt, long now) {
        if (now >= dueAt) return principal + principal * rate;
        return principal; // 未到期只显示本金
    }

    /** 还款 */
    public boolean repay(String loanId) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            try {
                CentralBankLoanRepaymentStore.Result result =
                        CentralBankLoanRepaymentStore.apply(conn, loanId);
                if (!result.paid()) {
                    conn.rollback();
                    return false;
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            eco.getLogger().warning("央行贷款还款失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 到期自动回收（类注释一直声称"到期自动扣除"但此前没有任何调用方——补上）。
     * 由 BankExtra 定时任务调用：逐笔尝试从银行总资产扣本息并标记已还，
     * 银行资产不足的留到下一轮重试并告警。
     */
    public void collectDueLoans() {
        List<String> dueIds = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM ks_bank_cb_loans WHERE repaid=0 AND due_at <= ?")) {
                ps.setLong(1, now);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) dueIds.add(rs.getString("id"));
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[央行贷款] 查询到期贷款失败: " + e.getMessage());
            return;
        }
        int ok = 0, fail = 0;
        for (String id : dueIds) {
            if (repay(id)) ok++;
            else fail++;
        }
        if (ok > 0) eco.getLogger().info("[央行贷款] 到期自动回收 " + ok + " 笔");
        if (fail > 0) eco.getLogger().warning("[央行贷款] " + fail + " 笔到期贷款因银行资产不足暂无法回收，下轮重试");
    }
}
