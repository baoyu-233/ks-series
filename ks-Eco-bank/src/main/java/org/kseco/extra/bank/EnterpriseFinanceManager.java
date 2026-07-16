package org.kseco.extra.bank;

import org.kseco.KsEco;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Enterprise lending is deliberately separate from player loans. Loan proceeds,
 * repayments, defaults, and collateral stay on the enterprise balance sheet.
 */
public final class EnterpriseFinanceManager {

    private static final Set<String> PURPOSES = Set.of(
            "EXPANSION", "REAL_ESTATE_DEVELOPMENT", "PROJECT_DEPOSIT", "LOGISTICS_NETWORK");
    private static final Set<String> COLLATERAL_TYPES = Set.of("PLOT", "HOUSE", "PROJECT_CONTRACT", "INVENTORY");
    private static final long DEFAULT_GRACE_SECONDS = 3L * 86400L;

    private final KsEco eco;

    public EnterpriseFinanceManager(KsEco eco) {
        this.eco = eco;
    }

    public void init() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_bank_enterprise_loan_requests (
                        id TEXT PRIMARY KEY,
                        bank_id TEXT NOT NULL,
                        enterprise_id TEXT NOT NULL,
                        requester_uuid TEXT NOT NULL,
                        purpose TEXT NOT NULL,
                        principal REAL NOT NULL,
                        term_days INTEGER NOT NULL,
                        collateral_type TEXT NOT NULL,
                        collateral_ref TEXT NOT NULL,
                        collateral_value REAL NOT NULL,
                        loan_to_value REAL NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        requested_at INTEGER NOT NULL,
                        decided_at INTEGER,
                        loan_id TEXT
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_bank_enterprise_loans (
                        id TEXT PRIMARY KEY,
                        bank_id TEXT NOT NULL,
                        enterprise_id TEXT NOT NULL,
                        purpose TEXT NOT NULL,
                        principal REAL NOT NULL,
                        remaining REAL NOT NULL,
                        interest_rate REAL NOT NULL,
                        term_days INTEGER NOT NULL,
                        issued_at INTEGER NOT NULL,
                        due_at INTEGER NOT NULL,
                        overdue_at INTEGER DEFAULT 0,
                        default_at INTEGER DEFAULT 0,
                        status TEXT NOT NULL DEFAULT 'ACTIVE'
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_bank_collateral (
                        id TEXT PRIMARY KEY,
                        loan_id TEXT,
                        enterprise_id TEXT NOT NULL,
                        bank_id TEXT NOT NULL,
                        asset_type TEXT NOT NULL,
                        asset_ref TEXT NOT NULL,
                        appraised_value REAL NOT NULL,
                        status TEXT NOT NULL DEFAULT 'LOCKED',
                        locked_at INTEGER NOT NULL,
                        released_at INTEGER DEFAULT 0,
                        UNIQUE(asset_type, asset_ref, status)
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_bank_collateral_auctions (
                        id TEXT PRIMARY KEY,
                        collateral_id TEXT NOT NULL,
                        bank_id TEXT NOT NULL,
                        asset_type TEXT NOT NULL,
                        asset_ref TEXT NOT NULL,
                        starting_price REAL NOT NULL,
                        current_price REAL NOT NULL,
                        highest_bidder_uuid TEXT,
                        status TEXT NOT NULL DEFAULT 'OPEN',
                        opens_at INTEGER NOT NULL,
                        closes_at INTEGER NOT NULL,
                        settled_at INTEGER DEFAULT 0
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_ent_inventory_lots (
                        id TEXT PRIMARY KEY,
                        enterprise_id TEXT NOT NULL,
                        description TEXT NOT NULL,
                        quantity INTEGER NOT NULL DEFAULT 1,
                        appraised_value REAL NOT NULL,
                        status TEXT NOT NULL DEFAULT 'AVAILABLE',
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_ent_loan_status ON ks_bank_enterprise_loans(status, due_at)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_ent_loan_ent ON ks_bank_enterprise_loans(enterprise_id, status)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_collateral_asset ON ks_bank_collateral(asset_type, asset_ref)");
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[企业融资] 建表失败: " + e.getMessage());
        }
    }

    public Map<String, Object> requestLoan(String bankId, String enterpriseId, UUID requesterUuid,
                                            double principal, int termDays, String purpose,
                                            String collateralType, String collateralRef, double loanToValue) {
        if (!validAmount(principal) || termDays < 1 || termDays > 3650) return fail("贷款金额或期限无效");
        purpose = normalized(purpose);
        collateralType = normalized(collateralType);
        if (!PURPOSES.contains(purpose)) return fail("贷款用途无效");
        if (!COLLATERAL_TYPES.contains(collateralType) || collateralRef == null || collateralRef.isBlank()) return fail("抵押物无效");
        if (!Double.isFinite(loanToValue) || loanToValue <= 0 || loanToValue > 0.75) return fail("抵押率必须在 0%-75% 之间");

        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return fail("数据库不可用");
            if (!isEnterpriseOwner(conn, enterpriseId, requesterUuid)) return fail("只有企业所有者可以申请融资");
            if (!isBankActive(conn, bankId)) return fail("目标银行不可用");
            double appraisal = appraiseCollateral(conn, enterpriseId, collateralType, collateralRef);
            if (appraisal <= 0) return fail("抵押物不存在、不属于该企业或已被抵押");
            if (principal > appraisal * loanToValue) return fail("贷款金额超过抵押率允许的额度");

            String id = "ER-" + shortId();
            long now = now();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO ks_bank_enterprise_loan_requests
                    (id,bank_id,enterprise_id,requester_uuid,purpose,principal,term_days,collateral_type,collateral_ref,collateral_value,loan_to_value,status,requested_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,'PENDING',?)
                """)) {
                ps.setString(1, id); ps.setString(2, bankId); ps.setString(3, enterpriseId); ps.setString(4, requesterUuid.toString());
                ps.setString(5, purpose); ps.setDouble(6, principal); ps.setInt(7, termDays); ps.setString(8, collateralType);
                ps.setString(9, collateralRef.trim()); ps.setDouble(10, appraisal); ps.setDouble(11, loanToValue); ps.setLong(12, now);
                ps.executeUpdate();
            }
            return success("企业融资申请已提交", Map.of("id", id, "collateralValue", appraisal));
        } catch (SQLException e) {
            eco.getLogger().warning("[企业融资] 提交申请失败: " + e.getMessage());
            return fail("融资申请提交失败");
        }
    }

    public boolean decideRequest(String requestId, boolean approve) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            try {
                Request request = loadPendingRequest(conn, requestId);
                if (request == null) { conn.rollback(); return false; }
                if (!approve) {
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE ks_bank_enterprise_loan_requests SET status='REJECTED',decided_at=? WHERE id=? AND status='PENDING'")) {
                        ps.setLong(1, now()); ps.setString(2, request.id());
                        boolean updated = ps.executeUpdate() == 1;
                        conn.commit();
                        return updated;
                    }
                }
                if (appraiseCollateral(conn, request.enterpriseId(), request.collateralType(), request.collateralRef()) < request.principal() / request.loanToValue()) {
                    conn.rollback(); return false;
                }
                if (!claimRequest(conn, request.id())) { conn.rollback(); return false; }

                CorporateAccount account = corporateAccount(conn, request.enterpriseId());
                if (account == null || !request.bankId().equals(account.bankId())) { conn.rollback(); return false; }
                double reserve = requiredReserve(conn, request.bankId());
                if (!debitBankForLoan(conn, request.bankId(), request.principal(), reserve)) { conn.rollback(); return false; }

                long now = now();
                String loanId = "EL-" + shortId();
                double rate = bankLoanRate(conn, request.bankId(), request.enterpriseId(), request.purpose());
                try (PreparedStatement loan = conn.prepareStatement("""
                        INSERT INTO ks_bank_enterprise_loans
                        (id,bank_id,enterprise_id,purpose,principal,remaining,interest_rate,term_days,issued_at,due_at,status)
                        VALUES (?,?,?,?,?,?,?,?,?,?, 'ACTIVE')
                    """)) {
                    loan.setString(1, loanId); loan.setString(2, request.bankId()); loan.setString(3, request.enterpriseId());
                    loan.setString(4, request.purpose()); loan.setDouble(5, request.principal());
                    loan.setDouble(6, request.principal() * (1 + rate)); loan.setDouble(7, rate); loan.setInt(8, request.termDays());
                    loan.setLong(9, now); loan.setLong(10, now + request.termDays() * 86400L); loan.executeUpdate();
                }
                String collateralId = "CO-" + shortId();
                try (PreparedStatement collateral = conn.prepareStatement("""
                        INSERT INTO ks_bank_collateral (id,loan_id,enterprise_id,bank_id,asset_type,asset_ref,appraised_value,status,locked_at)
                        VALUES (?,?,?,?,?,?,?,'LOCKED',?)
                    """)) {
                    collateral.setString(1, collateralId); collateral.setString(2, loanId); collateral.setString(3, request.enterpriseId());
                    collateral.setString(4, request.bankId()); collateral.setString(5, request.collateralType());
                    collateral.setString(6, request.collateralRef()); collateral.setDouble(7, request.collateralValue()); collateral.setLong(8, now);
                    collateral.executeUpdate();
                }
                lockProperty(conn, request.collateralType(), request.collateralRef());
                try (PreparedStatement accountCredit = conn.prepareStatement("UPDATE ks_ent_corporate_accounts SET balance=balance+?,updated_at=? WHERE enterprise_id=? AND bank_id=?")) {
                    accountCredit.setDouble(1, request.principal()); accountCredit.setLong(2, now);
                    accountCredit.setString(3, request.enterpriseId()); accountCredit.setString(4, request.bankId());
                    if (accountCredit.executeUpdate() != 1) throw new SQLException("企业公户状态已变化");
                }
                try (PreparedStatement assets = conn.prepareStatement("UPDATE ks_ent_enterprises SET current_assets=current_assets+? WHERE id=?")) {
                    assets.setDouble(1, request.principal()); assets.setString(2, request.enterpriseId()); assets.executeUpdate();
                }
                try (PreparedStatement complete = conn.prepareStatement("UPDATE ks_bank_enterprise_loan_requests SET status='APPROVED',decided_at=?,loan_id=? WHERE id=? AND status='PROCESSING'")) {
                    complete.setLong(1, now); complete.setString(2, loanId); complete.setString(3, request.id());
                    if (complete.executeUpdate() != 1) throw new SQLException("申请状态更新失败");
                }
                conn.commit();
                return true;
            } catch (Exception e) {
                conn.rollback();
                eco.getLogger().warning("[企业融资] 审批失败: " + e.getMessage());
                return false;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[企业融资] 审批失败: " + e.getMessage());
            return false;
        }
    }

    public Map<String, Object> repay(String loanId, String enterpriseId, UUID requesterUuid, double requestedAmount) {
        if (!validAmount(requestedAmount)) return fail("还款金额无效");
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return fail("数据库不可用");
            conn.setAutoCommit(false);
            try {
                if (!isEnterpriseOwner(conn, enterpriseId, requesterUuid)) { conn.rollback(); return fail("只有企业所有者可以还款"); }
                EnterpriseLoan loan = loadRepayableLoan(conn, loanId, enterpriseId);
                if (loan == null) { conn.rollback(); return fail("贷款不存在或不可还款"); }
                CorporateAccount account = corporateAccount(conn, enterpriseId);
                double payment = Math.min(requestedAmount, loan.remaining());
                if (account == null || account.balance() < payment) { conn.rollback(); return fail("企业公户余额不足"); }
                long now = now();
                try (PreparedStatement debit = conn.prepareStatement("UPDATE ks_ent_corporate_accounts SET balance=balance-?,updated_at=? WHERE enterprise_id=? AND balance>=?")) {
                    debit.setDouble(1, payment); debit.setLong(2, now); debit.setString(3, enterpriseId); debit.setDouble(4, payment);
                    if (debit.executeUpdate() != 1) throw new SQLException("公户扣款失败");
                }
                double remaining = loan.remaining() - payment;
                String status = remaining <= 0.01 ? "PAID" : loan.status();
                try (PreparedStatement update = conn.prepareStatement("UPDATE ks_bank_enterprise_loans SET remaining=?,status=? WHERE id=? AND remaining>=?")) {
                    update.setDouble(1, Math.max(0, remaining)); update.setString(2, status); update.setString(3, loan.id()); update.setDouble(4, payment);
                    if (update.executeUpdate() != 1) throw new SQLException("贷款状态已变化");
                }
                try (PreparedStatement bank = conn.prepareStatement("UPDATE ks_bank_banks SET total_assets=total_assets+? WHERE id=? AND status='ACTIVE'")) {
                    bank.setDouble(1, payment); bank.setString(2, loan.bankId());
                    if (bank.executeUpdate() != 1) throw new SQLException("贷款银行不可用");
                }
                try (PreparedStatement assets = conn.prepareStatement("UPDATE ks_ent_enterprises SET current_assets=MAX(current_assets-?,0) WHERE id=?")) {
                    assets.setDouble(1, payment); assets.setString(2, enterpriseId); assets.executeUpdate();
                }
                if ("PAID".equals(status)) releaseCollateral(conn, loan.id());
                conn.commit();
                return success("企业贷款还款成功", Map.of("payment", payment, "remaining", Math.max(0, remaining), "status", status));
            } catch (Exception e) {
                conn.rollback();
                return fail("企业贷款还款失败");
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            return fail("企业贷款还款失败");
        }
    }

    public boolean selectCorporateBank(String enterpriseId, UUID requesterUuid, String targetBankId) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null || !isEnterpriseOwner(conn, enterpriseId, requesterUuid) || !isBankActive(conn, targetBankId)) return false;
            conn.setAutoCommit(false);
            try {
                if (hasActiveLoans(conn, enterpriseId)) { conn.rollback(); return false; }
                CorporateAccount current = corporateAccount(conn, enterpriseId);
                if (current == null || current.bankId().equals(targetBankId)) { conn.rollback(); return current != null; }
                if (!moveCorporateDeposit(conn, enterpriseId, current, targetBankId)) { conn.rollback(); return false; }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback(); return false;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public List<Map<String, Object>> listEnterpriseLoans(String enterpriseId) {
        return rows("SELECT * FROM ks_bank_enterprise_loans WHERE enterprise_id=? ORDER BY issued_at DESC", enterpriseId);
    }

    public List<Map<String, Object>> listRequests(String bankId, String status) {
        String query = "SELECT * FROM ks_bank_enterprise_loan_requests WHERE bank_id=?" + (status == null ? "" : " AND status=?") + " ORDER BY requested_at DESC LIMIT 100";
        return rows(query, bankId, status);
    }

    public List<Map<String, Object>> listAuctions() {
        return rows("SELECT * FROM ks_bank_collateral_auctions WHERE status='OPEN' ORDER BY closes_at ASC LIMIT 100");
    }

    /** Vault is touched here, so callers must marshal this method onto the Bukkit main thread. */
    public Map<String, Object> placeAuctionBid(String auctionId, UUID bidderUuid, double amount) {
        if (!org.bukkit.Bukkit.isPrimaryThread() || !validAmount(amount)) return fail("竞价必须在服务器主线程执行且金额有效");
        var bidder = org.bukkit.Bukkit.getOfflinePlayer(bidderUuid);
        String previousBidder = null;
        double previousAmount = 0;
        boolean charged = false;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null || !eco.vaultHook().has(bidder, amount)) return fail("余额不足或数据库不可用");
            conn.setAutoCommit(false);
            try (PreparedStatement select = conn.prepareStatement("SELECT * FROM ks_bank_collateral_auctions WHERE id=? AND status='OPEN'")) {
                select.setString(1, auctionId);
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next() || rs.getLong("closes_at") <= now()) { conn.rollback(); return fail("拍卖已结束或不存在"); }
                    previousBidder = rs.getString("highest_bidder_uuid"); previousAmount = rs.getDouble("current_price");
                    double minimum = previousBidder == null ? rs.getDouble("starting_price") : previousAmount + 1;
                    if (amount < minimum) { conn.rollback(); return fail("出价未达到最低加价"); }
                }
            }
            if (!eco.vaultHook().withdraw(bidder, amount)) { conn.rollback(); return fail("扣除竞价资金失败"); }
            charged = true;
            try (PreparedStatement update = conn.prepareStatement("UPDATE ks_bank_collateral_auctions SET current_price=?,highest_bidder_uuid=? WHERE id=? AND status='OPEN'")) {
                update.setDouble(1, amount); update.setString(2, bidderUuid.toString()); update.setString(3, auctionId);
                if (update.executeUpdate() != 1) throw new SQLException("拍卖状态已变化");
            }
            conn.commit();
        } catch (Exception e) { if (charged) eco.vaultHook().deposit(bidder, amount); return fail("竞价失败"); }
        if (previousBidder != null) eco.vaultHook().deposit(org.bukkit.Bukkit.getOfflinePlayer(UUID.fromString(previousBidder)), previousAmount);
        return success("竞价成功", Map.of("amount", amount));
    }

    /** Called on the Bukkit thread: completes expired auctions without accessing player inventories. */
    public void settleExpiredAuctions() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM ks_bank_collateral_auctions WHERE status='OPEN' AND closes_at<=?")) {
                ps.setLong(1, now());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getString("id"), type = rs.getString("asset_type"), ref = rs.getString("asset_ref"), bank = rs.getString("bank_id"), bidder = rs.getString("highest_bidder_uuid");
                        if (bidder != null) transferAuctionProperty(conn, type, ref, bidder);
                        try (PreparedStatement close = conn.prepareStatement("UPDATE ks_bank_collateral_auctions SET status=?,settled_at=? WHERE id=?")) {
                            close.setString(1, bidder == null ? "UNSOLD" : "SOLD"); close.setLong(2, now()); close.setString(3, id); close.executeUpdate();
                        }
                        if (bidder != null) try (PreparedStatement credit = conn.prepareStatement("UPDATE ks_bank_banks SET total_assets=total_assets+? WHERE id=?")) { credit.setDouble(1, rs.getDouble("current_price")); credit.setString(2, bank); credit.executeUpdate(); }
                    }
                }
            }
            conn.commit();
        } catch (SQLException e) { eco.getLogger().warning("[企业融资] 拍卖结算失败: " + e.getMessage()); }
    }

    private void transferAuctionProperty(Connection conn, String type, String ref, String buyerUuid) throws SQLException {
        if ("PLOT".equals(type)) try (PreparedStatement ps = conn.prepareStatement("UPDATE ks_re_plots SET owner_type='PLAYER',owner_id=?,status='PURCHASED' WHERE id=?")) { ps.setString(1, buyerUuid); ps.setString(2, ref); ps.executeUpdate(); }
        else if ("HOUSE".equals(type)) try (PreparedStatement ps = conn.prepareStatement("UPDATE ks_re_houses SET owner_type='PLAYER',owner_id=? WHERE id=?")) { ps.setString(1, buyerUuid); ps.setString(2, ref); ps.executeUpdate(); }
        else if ("INVENTORY".equals(type)) try (PreparedStatement ps = conn.prepareStatement("UPDATE ks_ent_inventory_lots SET status='SOLD',updated_at=? WHERE id=?")) { ps.setLong(1, now()); ps.setString(2, ref); ps.executeUpdate(); }
    }

    public String registerInventoryLot(String enterpriseId, String description, int quantity, double appraisedValue) {
        if (enterpriseId == null || enterpriseId.isBlank() || description == null || description.isBlank()
                || quantity < 1 || !validAmount(appraisedValue)) return null;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement enterprise = conn.prepareStatement("SELECT 1 FROM ks_ent_enterprises WHERE id=?")) {
                enterprise.setString(1, enterpriseId);
                if (!enterprise.executeQuery().next()) return null;
            }
            String id = "IV-" + shortId();
            long now = now();
            try (PreparedStatement insert = conn.prepareStatement("""
                    INSERT INTO ks_ent_inventory_lots (id,enterprise_id,description,quantity,appraised_value,status,created_at,updated_at)
                    VALUES (?,?,?,?,?,'AVAILABLE',?,?)
                """)) {
                insert.setString(1, id); insert.setString(2, enterpriseId); insert.setString(3, description.trim());
                insert.setInt(4, quantity); insert.setDouble(5, appraisedValue); insert.setLong(6, now); insert.setLong(7, now);
                insert.executeUpdate();
            }
            return id;
        } catch (SQLException e) {
            eco.getLogger().warning("[企业融资] 企业库存入库失败: " + e.getMessage());
            return null;
        }
    }

    public List<Map<String, Object>> listInventoryLots(String enterpriseId) {
        return rows("SELECT * FROM ks_ent_inventory_lots WHERE enterprise_id=? ORDER BY created_at DESC", enterpriseId);
    }

    /** Database-only maintenance; it is safe to run from the shared bank work pool. */
    public void maintainDefaults() {
        long now = now();
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            conn.setAutoCommit(false);
            try {
                List<EnterpriseLoan> overdue = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM ks_bank_enterprise_loans WHERE status='ACTIVE' AND due_at<?")) {
                    ps.setLong(1, now);
                    try (ResultSet rs = ps.executeQuery()) { while (rs.next()) overdue.add(loanRow(rs)); }
                }
                for (EnterpriseLoan loan : overdue) {
                    try (PreparedStatement mark = conn.prepareStatement("UPDATE ks_bank_enterprise_loans SET status='OVERDUE',overdue_at=? WHERE id=? AND status='ACTIVE'")) {
                        mark.setLong(1, now); mark.setString(2, loan.id()); mark.executeUpdate();
                    }
                    try (PreparedStatement freeze = conn.prepareStatement("UPDATE ks_ent_enterprises SET status='FROZEN' WHERE id=? AND status='ACTIVE'")) {
                        freeze.setString(1, loan.enterpriseId()); freeze.executeUpdate();
                    }
                }
                List<EnterpriseLoan> defaults = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM ks_bank_enterprise_loans WHERE status='OVERDUE' AND overdue_at>0 AND overdue_at<?")) {
                    ps.setLong(1, now - DEFAULT_GRACE_SECONDS);
                    try (ResultSet rs = ps.executeQuery()) { while (rs.next()) defaults.add(loanRow(rs)); }
                }
                for (EnterpriseLoan loan : defaults) seizeCollateralAndOpenAuction(conn, loan, now);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                eco.getLogger().warning("[企业融资] 违约处置失败: " + e.getMessage());
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[企业融资] 违约处置失败: " + e.getMessage());
        }
    }

    private void seizeCollateralAndOpenAuction(Connection conn, EnterpriseLoan loan, long now) throws SQLException {
        List<Collateral> collateral = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM ks_bank_collateral WHERE loan_id=? AND status='LOCKED'")) {
            ps.setString(1, loan.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) collateral.add(new Collateral(rs.getString("id"), rs.getString("asset_type"), rs.getString("asset_ref"), rs.getDouble("appraised_value")));
            }
        }
        for (Collateral item : collateral) {
            transferForeclosedProperty(conn, item.assetType(), item.assetRef(), loan.bankId());
            try (PreparedStatement seize = conn.prepareStatement("UPDATE ks_bank_collateral SET status='SEIZED',released_at=? WHERE id=? AND status='LOCKED'")) {
                seize.setLong(1, now); seize.setString(2, item.id()); seize.executeUpdate();
            }
            try (PreparedStatement auction = conn.prepareStatement("""
                    INSERT INTO ks_bank_collateral_auctions
                    (id,collateral_id,bank_id,asset_type,asset_ref,starting_price,current_price,status,opens_at,closes_at)
                    VALUES (?,?,?,?,?,?,?,'OPEN',?,?)
                """)) {
                double start = Math.max(1, item.value() * 0.60);
                auction.setString(1, "AU-" + shortId()); auction.setString(2, item.id()); auction.setString(3, loan.bankId());
                auction.setString(4, item.assetType()); auction.setString(5, item.assetRef()); auction.setDouble(6, start); auction.setDouble(7, start);
                auction.setLong(8, now); auction.setLong(9, now + 7L * 86400L); auction.executeUpdate();
            }
        }
        try (PreparedStatement loanUpdate = conn.prepareStatement("UPDATE ks_bank_enterprise_loans SET status='DEFAULTED',default_at=? WHERE id=? AND status='OVERDUE'")) {
            loanUpdate.setLong(1, now); loanUpdate.setString(2, loan.id()); loanUpdate.executeUpdate();
        }
    }

    private double appraiseCollateral(Connection conn, String enterpriseId, String type, String assetRef) throws SQLException {
        if (isCollateralLocked(conn, type, assetRef)) return 0;
        if ("PLOT".equals(type)) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT price FROM ks_re_plots WHERE id=? AND owner_type='ENTERPRISE' AND owner_id=? AND status='PURCHASED'")) {
                ps.setString(1, assetRef); ps.setString(2, enterpriseId);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getDouble(1) : 0; }
            }
        }
        if ("HOUSE".equals(type)) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT p.price FROM ks_re_houses h JOIN ks_re_plots p ON p.id=h.plot_id
                    WHERE h.id=? AND h.owner_type='ENTERPRISE' AND h.owner_id=? AND p.status='PURCHASED'
                """)) {
                ps.setString(1, assetRef); ps.setString(2, enterpriseId);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getDouble(1) : 0; }
            }
        }
        if ("INVENTORY".equals(type)) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT appraised_value FROM ks_ent_inventory_lots WHERE id=? AND enterprise_id=? AND status='AVAILABLE'")) {
                ps.setString(1, assetRef); ps.setString(2, enterpriseId);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getDouble(1) : 0; }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT p.budget FROM ks_ent_projects p JOIN ks_ent_bids b ON b.project_id=p.id
                WHERE p.id=? AND b.enterprise_id=? AND p.status='AWARDED' AND b.status='AWARDED'
            """)) {
            ps.setString(1, assetRef); ps.setString(2, enterpriseId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getDouble(1) : 0; }
        }
    }

    private boolean isCollateralLocked(Connection conn, String type, String ref) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM ks_bank_collateral WHERE asset_type=? AND asset_ref=? AND status IN ('LOCKED','SEIZED')")) {
            ps.setString(1, type); ps.setString(2, ref);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private void lockProperty(Connection conn, String type, String ref) throws SQLException {
        if ("PLOT".equals(type)) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE ks_re_plots SET status='MORTGAGED' WHERE id=? AND status='PURCHASED'")) { ps.setString(1, ref); ps.executeUpdate(); }
        } else if ("HOUSE".equals(type)) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE ks_re_houses SET name=COALESCE(name,'房屋') WHERE id=?")) { ps.setString(1, ref); ps.executeUpdate(); }
        }
        else if ("INVENTORY".equals(type)) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE ks_ent_inventory_lots SET status='PLEDGED',updated_at=? WHERE id=? AND status='AVAILABLE'")) {
                ps.setLong(1, now()); ps.setString(2, ref); ps.executeUpdate();
            }
        }
    }

    private void releaseCollateral(Connection conn, String loanId) throws SQLException {
        List<Collateral> released = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id,asset_type,asset_ref,appraised_value FROM ks_bank_collateral WHERE loan_id=? AND status='LOCKED'")) {
            ps.setString(1, loanId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) released.add(new Collateral(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDouble(4))); }
        }
        for (Collateral item : released) {
            if ("PLOT".equals(item.assetType())) {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE ks_re_plots SET status='PURCHASED' WHERE id=? AND status='MORTGAGED'")) { ps.setString(1, item.assetRef()); ps.executeUpdate(); }
            }
            else if ("INVENTORY".equals(item.assetType())) {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE ks_ent_inventory_lots SET status='AVAILABLE',updated_at=? WHERE id=? AND status='PLEDGED'")) { ps.setLong(1, now()); ps.setString(2, item.assetRef()); ps.executeUpdate(); }
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE ks_bank_collateral SET status='RELEASED',released_at=? WHERE id=?")) {
                ps.setLong(1, now()); ps.setString(2, item.id()); ps.executeUpdate();
            }
        }
    }

    private void transferForeclosedProperty(Connection conn, String type, String ref, String bankId) throws SQLException {
        if ("PLOT".equals(type)) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE ks_re_plots SET owner_type='BANK',owner_id=?,status='FORECLOSED' WHERE id=?")) { ps.setString(1, bankId); ps.setString(2, ref); ps.executeUpdate(); }
        } else if ("HOUSE".equals(type)) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE ks_re_houses SET owner_type='BANK',owner_id=? WHERE id=?")) { ps.setString(1, bankId); ps.setString(2, ref); ps.executeUpdate(); }
        }
        else if ("INVENTORY".equals(type)) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE ks_ent_inventory_lots SET status='SEIZED',updated_at=? WHERE id=?")) { ps.setLong(1, now()); ps.setString(2, ref); ps.executeUpdate(); }
        }
    }

    private boolean isEnterpriseOwner(Connection conn, String enterpriseId, UUID playerUuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT owner_uuids FROM ks_ent_enterprises WHERE id=? AND status IN ('ACTIVE','FROZEN')")) {
            ps.setString(1, enterpriseId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                for (String value : rs.getString(1).split(",")) if (playerUuid.toString().equals(value.trim())) return true;
                var provider = eco.enterpriseAccessProvider();
                return provider != null && provider.hasPermission(enterpriseId, playerUuid, "MANAGE_FUNDS");
            }
        }
    }

    private boolean isBankActive(Connection conn, String bankId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM ks_bank_banks WHERE id=? AND status='ACTIVE' AND type!='CENTRAL'")) {
            ps.setString(1, bankId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private boolean hasActiveLoans(Connection conn, String enterpriseId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM ks_bank_enterprise_loans WHERE enterprise_id=? AND status IN ('ACTIVE','OVERDUE')")) {
            ps.setString(1, enterpriseId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private CorporateAccount corporateAccount(Connection conn, String enterpriseId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT bank_id,balance FROM ks_ent_corporate_accounts WHERE enterprise_id=?")) {
            ps.setString(1, enterpriseId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? new CorporateAccount(rs.getString(1), rs.getDouble(2)) : null; }
        }
    }

    private boolean moveCorporateDeposit(Connection conn, String enterpriseId, CorporateAccount current, String targetBankId) throws SQLException {
        if (current.balance() > 0) {
            try (PreparedStatement out = conn.prepareStatement("UPDATE ks_bank_banks SET total_assets=total_assets-? WHERE id=? AND total_assets>=?")) {
                out.setDouble(1, current.balance()); out.setString(2, current.bankId()); out.setDouble(3, current.balance());
                if (out.executeUpdate() != 1) return false;
            }
            try (PreparedStatement in = conn.prepareStatement("UPDATE ks_bank_banks SET total_assets=total_assets+? WHERE id=? AND status='ACTIVE'")) {
                in.setDouble(1, current.balance()); in.setString(2, targetBankId);
                if (in.executeUpdate() != 1) return false;
            }
        }
        try (PreparedStatement update = conn.prepareStatement("UPDATE ks_ent_corporate_accounts SET bank_id=?,updated_at=? WHERE enterprise_id=? AND bank_id=?")) {
            update.setString(1, targetBankId); update.setLong(2, now()); update.setString(3, enterpriseId); update.setString(4, current.bankId());
            return update.executeUpdate() == 1;
        }
    }

    private double requiredReserve(Connection conn, String bankId) throws SQLException {
        double ratio = 0;
        try (PreparedStatement ps = conn.prepareStatement("SELECT reserve_ratio FROM ks_bank_banks WHERE id=?")) {
            ps.setString(1, bankId); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) ratio = rs.getDouble(1); }
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT reserve_requirement FROM ks_bank_cb_rates ORDER BY set_at DESC LIMIT 1"); ResultSet rs = ps.executeQuery()) {
            if (rs.next()) ratio = Math.max(ratio, rs.getDouble(1));
        }
        ratio = Math.max(0, Math.min(1, ratio + activeReserveDelta(conn)));
        double deposits = 0;
        try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(SUM(balance),0) FROM ks_bank_accounts WHERE bank_id=?")) {
            ps.setString(1, bankId); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) deposits += rs.getDouble(1); }
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(SUM(balance),0) FROM ks_ent_corporate_accounts WHERE bank_id=?")) {
            ps.setString(1, bankId); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) deposits += rs.getDouble(1); }
        }
        return deposits * ratio;
    }

    private boolean debitBankForLoan(Connection conn, String bankId, double amount, double reserve) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE ks_bank_banks SET total_assets=total_assets-? WHERE id=? AND status='ACTIVE' AND total_assets>=?")) {
            ps.setDouble(1, amount); ps.setString(2, bankId); ps.setDouble(3, amount + reserve);
            return ps.executeUpdate() == 1;
        }
    }

    private double bankLoanRate(Connection conn, String bankId, String enterpriseId, String purpose) throws SQLException {
        double baseRate;
        try (PreparedStatement ps = conn.prepareStatement("SELECT loan_rate FROM ks_bank_banks WHERE id=?")) {
            ps.setString(1, bankId); try (ResultSet rs = ps.executeQuery()) { baseRate = rs.next() ? Math.max(0, rs.getDouble(1)) : 0.08; }
        }
        return Math.max(0, Math.min(1, baseRate * activeLoanRateMultiplier(conn, enterpriseId, purpose)));
    }

    private double activeLoanRateMultiplier(Connection conn, String enterpriseId, String purpose) throws SQLException {
        String industry = "OTHER";
        try (PreparedStatement ps = conn.prepareStatement("SELECT industry FROM ks_ent_enterprises WHERE id=?")) {
            ps.setString(1, enterpriseId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next() && rs.getString(1) != null) industry = normalized(rs.getString(1)); }
        }
        long now = now();
        double multiplier = 1.0;
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT policy_industry,policy_purpose,policy_loan_rate_multiplier FROM ks_major_orders
                WHERE status='ACTIVE' AND (starts_at=0 OR starts_at<=?) AND (ends_at=0 OR ends_at>=?)
            """)) {
            ps.setLong(1, now); ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String targetIndustry = normalized(rs.getString(1));
                    String targetPurpose = normalized(rs.getString(2));
                    if (("ALL".equals(targetIndustry) || targetIndustry.equals(industry))
                            && ("ALL".equals(targetPurpose) || targetPurpose.equals(normalized(purpose)))) {
                        multiplier *= Math.max(0.1, Math.min(3.0, rs.getDouble(3)));
                    }
                }
            }
        }
        return Math.max(0.1, Math.min(3.0, multiplier));
    }

    private double activeReserveDelta(Connection conn) throws SQLException {
        long now = now();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COALESCE(SUM(policy_reserve_delta),0) FROM ks_major_orders
                WHERE status='ACTIVE' AND (starts_at=0 OR starts_at<=?) AND (ends_at=0 OR ends_at>=?)
            """)) {
            ps.setLong(1, now); ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getDouble(1) : 0; }
        }
    }

    private Request loadPendingRequest(Connection conn, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM ks_bank_enterprise_loan_requests WHERE id=? AND status='PENDING'")) {
            ps.setString(1, id); try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Request(rs.getString("id"), rs.getString("bank_id"), rs.getString("enterprise_id"), rs.getString("purpose"),
                        rs.getDouble("principal"), rs.getInt("term_days"), rs.getString("collateral_type"), rs.getString("collateral_ref"),
                        rs.getDouble("collateral_value"), rs.getDouble("loan_to_value")) : null;
            }
        }
    }

    private boolean claimRequest(Connection conn, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE ks_bank_enterprise_loan_requests SET status='PROCESSING' WHERE id=? AND status='PENDING'")) {
            ps.setString(1, id); return ps.executeUpdate() == 1;
        }
    }

    private EnterpriseLoan loadRepayableLoan(Connection conn, String loanId, String enterpriseId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM ks_bank_enterprise_loans WHERE id=? AND enterprise_id=? AND status IN ('ACTIVE','OVERDUE')")) {
            ps.setString(1, loanId); ps.setString(2, enterpriseId); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? loanRow(rs) : null; }
        }
    }

    private EnterpriseLoan loanRow(ResultSet rs) throws SQLException {
        return new EnterpriseLoan(rs.getString("id"), rs.getString("bank_id"), rs.getString("enterprise_id"),
                rs.getDouble("remaining"), rs.getString("status"));
    }

    private List<Map<String, Object>> rows(String sql, Object... params) {
        List<Map<String, Object>> output = new ArrayList<>();
        try (Connection conn = eco.ksCore().dataStore().getConnection(); PreparedStatement ps = conn == null ? null : conn.prepareStatement(sql)) {
            if (ps == null) return output;
            int index = 1;
            for (Object value : params) if (value != null) ps.setObject(index++, value);
            try (ResultSet rs = ps.executeQuery()) {
                int columns = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columns; i++) row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    output.add(row);
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[企业融资] 查询失败: " + e.getMessage());
        }
        return output;
    }

    private static boolean validAmount(double amount) { return Double.isFinite(amount) && amount > 0 && amount <= 1_000_000_000_000d; }
    private static long now() { return System.currentTimeMillis() / 1000; }
    private static String shortId() { return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT); }
    private static String normalized(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static Map<String, Object> success(String message, Map<String, Object> details) { Map<String, Object> value = new LinkedHashMap<>(details); value.put("success", true); value.put("message", message); return value; }
    private static Map<String, Object> fail(String message) { return Map.of("success", false, "error", message); }

    private record Request(String id, String bankId, String enterpriseId, String purpose, double principal, int termDays,
                           String collateralType, String collateralRef, double collateralValue, double loanToValue) {}
    private record EnterpriseLoan(String id, String bankId, String enterpriseId, double remaining, String status) {}
    private record CorporateAccount(String bankId, double balance) {}
    private record Collateral(String id, String assetType, String assetRef, double value) {}
}
