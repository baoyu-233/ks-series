package org.kseco.extra.bank;

import org.kseco.KsEco;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Player-facing bank products and the commercial-bank operating dashboard. */
public final class BankGameplayManager {
    private static final long DAY = 86_400L;
    private final KsEco eco;
    private final BankManager bankManager;

    public BankGameplayManager(KsEco eco, BankManager bankManager) {
        this.eco = eco;
        this.bankManager = bankManager;
    }

    public void init() {
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) throw new SQLException("database unavailable");
            BankSchema.initializeGameplay(connection);
            seedProducts(connection);
            assessAllBanks(connection);
        } catch (SQLException failure) {
            throw new IllegalStateException("Bank gameplay schema initialization failed", failure);
        }
    }

    public void maintain() {
        settleMaturedDeposits(null);
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection != null) {
                maintainPolicyEvents(connection);
                assessAllBanks(connection);
            }
        } catch (SQLException failure) {
            eco.getLogger().warning("[银行经营] 风险评估失败: " + failure.getMessage());
        }
    }

    public Map<String, Object> dashboard(UUID playerUuid) {
        if (playerUuid == null) return Map.of("available", false, "reason", "玩家身份无效");
        settleMaturedDeposits(playerUuid);
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> accounts = new ArrayList<>();
        List<Map<String, Object>> terms = new ArrayList<>();
        List<Map<String, Object>> loans = new ArrayList<>();
        double demandTotal = 0;
        double termTotal = 0;
        double loanTotal = 0;
        double nextPayment = 0;
        double insuredCoverage = 0;
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return Map.of("available", false, "reason", "数据库不可用");
            seedProducts(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT a.bank_id,b.name AS bank_name,a.balance,a.interest_earned,b.interest_rate,b.loan_rate
                    FROM ks_bank_accounts a JOIN ks_bank_banks b ON b.id=a.bank_id
                    WHERE a.player_uuid=? ORDER BY a.balance DESC,b.name
                    """)) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Map<String, Object> row = row(rows);
                        demandTotal += rows.getDouble("balance");
                        accounts.add(row);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT d.*,b.name AS bank_name,p.name AS product_name
                    FROM ks_bank_term_deposits d
                    JOIN ks_bank_banks b ON b.id=d.bank_id
                    LEFT JOIN ks_bank_deposit_products p ON p.bank_id=d.bank_id AND p.product_code=d.product_code
                    WHERE d.player_uuid=? ORDER BY CASE WHEN d.status='ACTIVE' THEN 0 ELSE 1 END,d.matures_at
                    LIMIT 100
                    """)) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Map<String, Object> row = row(rows);
                        if ("ACTIVE".equals(rows.getString("status"))) termTotal += rows.getDouble("principal");
                        terms.add(row);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT l.*,b.name AS bank_name FROM ks_bank_loans l
                    JOIN ks_bank_banks b ON b.id=l.bank_id
                    WHERE l.borrower_uuid=? ORDER BY l.issued_at DESC LIMIT 100
                    """)) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Map<String, Object> row = row(rows);
                        String status = rows.getString("status");
                        if ("ACTIVE".equals(status) || "OVERDUE".equals(status)) {
                            loanTotal += rows.getDouble("remaining");
                        }
                        loans.add(row);
                    }
                }
            }
            for (Map<String, Object> loan : loans) ensureLoanSchedule(connection, loan);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT bank_id,SUM(amount) FROM (
                      SELECT bank_id,balance AS amount FROM ks_bank_accounts WHERE player_uuid=?
                      UNION ALL
                      SELECT bank_id,principal AS amount FROM ks_bank_term_deposits WHERE player_uuid=? AND status='ACTIVE'
                    ) insured GROUP BY bank_id
                    """)) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, playerUuid.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) insuredCoverage += Math.min(100_000, Math.max(0, rows.getDouble(2)));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COALESCE(SUM(principal_due+interest_due-paid_amount),0)
                    FROM ks_bank_loan_schedules s JOIN ks_bank_loans l ON l.id=s.loan_id
                    WHERE l.borrower_uuid=? AND s.status IN ('PENDING','OVERDUE')
                      AND s.installment_no=(SELECT MIN(s2.installment_no) FROM ks_bank_loan_schedules s2
                                           WHERE s2.loan_id=s.loan_id AND s2.status IN ('PENDING','OVERDUE'))
                    """)) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) nextPayment = rows.getDouble(1);
                }
            }
        } catch (SQLException failure) {
            eco.getLogger().warning("[银行经营] 玩家总览读取失败: " + failure.getMessage());
            return Map.of("available", false, "reason", "银行资料读取失败");
        }
        result.put("available", true);
        result.put("demandDeposits", demandTotal);
        result.put("termDeposits", termTotal);
        result.put("totalDeposits", demandTotal + termTotal);
        result.put("loanOutstanding", loanTotal);
        result.put("netBankPosition", demandTotal + termTotal - loanTotal);
        result.put("nextPayment", nextPayment);
        result.put("insuredCoverage", insuredCoverage);
        result.put("accounts", accounts);
        result.put("termDepositsList", terms);
        result.put("loans", loans);
        result.put("credit", bankManager.creditProfile(playerUuid));
        result.put("loanProducts", loanProducts());
        return result;
    }

    public List<Map<String, Object>> depositProducts(String bankId) {
        List<Map<String, Object>> products = new ArrayList<>();
        if (bankId == null || bankId.isBlank()) return products;
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return products;
            seedProducts(connection, bankId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT p.*,b.name AS bank_name FROM ks_bank_deposit_products p
                    JOIN ks_bank_banks b ON b.id=p.bank_id
                    WHERE p.bank_id=? AND p.active=1 ORDER BY p.term_days,p.min_amount
                    """)) {
                statement.setString(1, bankId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) products.add(row(rows));
                }
            }
        } catch (SQLException failure) {
            eco.getLogger().warning("[银行经营] 存款产品读取失败: " + failure.getMessage());
        }
        return products;
    }

    public Map<String, Object> openTermDeposit(String bankId, UUID playerUuid, String productCode,
                                                double amount, boolean autoRenew) {
        if (bankId == null || bankId.isBlank() || playerUuid == null || productCode == null
                || !Double.isFinite(amount) || amount <= 0) return fail("存款参数无效");
        long amountMinor;
        try {
            amountMinor = BankInterestSettlementStore.toMinor(amount);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return fail("存款金额无效");
        }
        String id = "TD-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT);
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return fail("数据库不可用");
            connection.setAutoCommit(false);
            try {
                seedProducts(connection, bankId);
                Product product = findProduct(connection, bankId, productCode);
                if (product == null || !product.active()) {
                    connection.rollback();
                    return fail("存款产品不存在或已停售");
                }
                String operating = operatingStatus(connection, bankId);
                if ("RESTRICTED".equals(operating) || "RESOLUTION".equals(operating)) {
                    connection.rollback();
                    return fail("该银行处于风险处置状态，暂停新增定期存款");
                }
                double settledAmount = BankInterestSettlementStore.fromMinor(amountMinor);
                if (settledAmount < product.minAmount()) {
                    connection.rollback();
                    return fail("低于产品起存金额 " + product.minAmount());
                }
                String accountId = bankId + ":" + playerUuid;
                BankInterestSettlementStore.SettlementResult mutation = bankManager.mutateAccountBalance(
                        connection, bankId, accountId, playerUuid, now(), -amountMinor, false);
                if (!mutation.applied()) {
                    connection.rollback();
                    return fail("活期余额不足，请先向该银行存款");
                }
                long openedAt = now();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ks_bank_term_deposits
                        (id,bank_id,player_uuid,product_code,principal,fixed_rate,term_days,opened_at,matures_at,
                         auto_renew,accrued_interest,status,version)
                        VALUES (?,?,?,?,?,?,?,?,?,?,0,'ACTIVE',0)
                        """)) {
                    statement.setString(1, id);
                    statement.setString(2, bankId);
                    statement.setString(3, playerUuid.toString());
                    statement.setString(4, product.code());
                    statement.setDouble(5, settledAmount);
                    statement.setDouble(6, product.fixedRate());
                    statement.setInt(7, product.termDays());
                    statement.setLong(8, openedAt);
                    statement.setLong(9, openedAt + product.termDays() * DAY);
                    statement.setInt(10, autoRenew ? 1 : 0);
                    statement.executeUpdate();
                }
                connection.commit();
                return success("定期存款已生效", Map.of(
                        "id", id, "principal", settledAmount, "fixedRate", product.fixedRate(),
                        "termDays", product.termDays(), "maturesAt", openedAt + product.termDays() * DAY));
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            eco.getLogger().warning("[银行经营] 开立定期失败: " + failure.getMessage());
            return fail("开立失败，请稍后重试");
        }
    }

    public Map<String, Object> redeemTermDeposit(String depositId, UUID playerUuid) {
        if (depositId == null || depositId.isBlank() || playerUuid == null) return fail("存单参数无效");
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return fail("数据库不可用");
            connection.setAutoCommit(false);
            try {
                TermDeposit deposit = findTermDeposit(connection, depositId, playerUuid);
                if (deposit == null || !"ACTIVE".equals(deposit.status())) {
                    connection.rollback();
                    return fail("存单不存在或已经结清");
                }
                Product product = findProduct(connection, deposit.bankId(), deposit.productCode());
                double penalty = product == null ? 1.0 : product.earlyPenaltyRate();
                long current = now();
                TermPayout calculated = calculateTermPayout(deposit.principal(), deposit.fixedRate(), penalty,
                        deposit.openedAt(), deposit.maturesAt(), current);
                boolean matured = calculated.matured();
                double interest = calculated.interest();
                double payout = calculated.payout();
                long payoutMinor = BankInterestSettlementStore.toMinor(payout);
                BankInterestSettlementStore.SettlementResult mutation = bankManager.mutateAccountBalance(
                        connection, deposit.bankId(), deposit.bankId() + ":" + playerUuid,
                        playerUuid, current, payoutMinor, true);
                if (!mutation.applied()) {
                    connection.rollback();
                    return fail("银行当前不可结算该存单");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE ks_bank_term_deposits SET status=?,accrued_interest=?,redeemed_at=?,version=version+1
                        WHERE id=? AND player_uuid=? AND status='ACTIVE' AND version=?
                        """)) {
                    statement.setString(1, matured ? "MATURED" : "EARLY_REDEEMED");
                    statement.setDouble(2, interest);
                    statement.setLong(3, current);
                    statement.setString(4, deposit.id());
                    statement.setString(5, playerUuid.toString());
                    statement.setLong(6, deposit.version());
                    if (statement.executeUpdate() != 1) {
                        connection.rollback();
                        return fail("存单状态已经变化，请刷新页面");
                    }
                }
                connection.commit();
                return success(matured ? "存单到期结清" : "存单已提前支取", Map.of(
                        "principal", deposit.principal(), "interest", interest, "payout", payout,
                        "early", !matured));
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            eco.getLogger().warning("[银行经营] 存单支取失败: " + failure.getMessage());
            return fail("支取失败，请稍后重试");
        }
    }

    public Map<String, Object> bankOperations(String bankId) {
        if (bankId == null || bankId.isBlank()) return fail("银行参数无效");
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return fail("数据库不可用");
            return assessBank(connection, bankId, true);
        } catch (SQLException failure) {
            eco.getLogger().warning("[银行经营] 经营报表读取失败: " + failure.getMessage());
            return fail("经营报表读取失败");
        }
    }

    public Map<String, Object> declareDividend(String bankId, UUID declaredBy, double requestedAmount) {
        if (bankId == null || bankId.isBlank() || declaredBy == null
                || !Double.isFinite(requestedAmount) || requestedAmount <= 0) return fail("分红参数无效");
        long totalMinor;
        try {
            totalMinor = BankInterestSettlementStore.toMinor(requestedAmount);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return fail("分红金额无效");
        }
        String batchId = "BD-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT);
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return fail("数据库不可用");
            connection.setAutoCommit(false);
            try {
                Map<String, Object> report = assessBank(connection, bankId, false);
                if (!Boolean.TRUE.equals(report.get("success"))) {
                    connection.rollback();
                    return report;
                }
                String operating = String.valueOf(report.get("operatingStatus"));
                if (!"NORMAL".equals(operating)) {
                    connection.rollback();
                    return fail("银行处于风险观察或处置状态，禁止分红");
                }
                double amount = BankInterestSettlementStore.fromMinor(totalMinor);
                double retained = number(report.get("retainedEarnings"));
                if (amount > retained + 0.000_001) {
                    connection.rollback();
                    return fail("超过当前可分配利润 " + String.format(Locale.ROOT, "%.2f", retained));
                }
                double liquidity = number(report.get("liquidity"));
                double deposits = number(report.get("totalDeposits"));
                double requiredReserve = Math.max(0.10, number(report.get("reserveRatio"))) * (deposits + amount);
                if (liquidity + 0.000_001 < requiredReserve) {
                    connection.rollback();
                    return fail("分红后准备金不足，至少需保留 " + String.format(Locale.ROOT, "%.2f", requiredReserve));
                }
                BankEquityManager.ensureInitialized(connection, bankId);
                List<BankEquityManager.Shareholder> shareholders = BankEquityManager.shareholders(connection, bankId);
                if (shareholders.isEmpty()) {
                    connection.rollback();
                    return fail("银行没有可结算的玩家股东");
                }
                long totalShares = shareholders.stream().mapToLong(BankEquityManager.Shareholder::shares).sum();
                if (totalShares <= 0) {
                    connection.rollback();
                    return fail("银行股本登记无效");
                }
                try (PreparedStatement batch = connection.prepareStatement("""
                        INSERT INTO ks_bank_dividend_batches
                        (id,bank_id,amount,recipient_count,status,declared_by,declared_at)
                        VALUES (?,?,?,?, 'FINALIZED',?,?)
                        """)) {
                    batch.setString(1, batchId);
                    batch.setString(2, bankId);
                    batch.setDouble(3, amount);
                    batch.setInt(4, shareholders.size());
                    batch.setString(5, declaredBy.toString());
                    batch.setLong(6, now());
                    batch.executeUpdate();
                }
                long allocated = 0;
                for (int index = 0; index < shareholders.size(); index++) {
                    BankEquityManager.Shareholder shareholder = shareholders.get(index);
                    UUID owner = shareholder.playerUuid();
                    long shareMinor = index == shareholders.size() - 1
                            ? totalMinor - allocated
                            : BigInteger.valueOf(totalMinor).multiply(BigInteger.valueOf(shareholder.shares()))
                                    .divide(BigInteger.valueOf(totalShares)).longValueExact();
                    allocated += shareMinor;
                    BankInterestSettlementStore.SettlementResult mutation = bankManager.mutateAccountBalance(
                            connection, bankId, bankId + ":" + owner, owner, now(), shareMinor, true);
                    if (!mutation.applied()) throw new SQLException("shareholder account credit rejected");
                    try (PreparedStatement payout = connection.prepareStatement(
                            "INSERT INTO ks_bank_dividend_payouts (batch_id,player_uuid,amount) VALUES (?,?,?)")) {
                        payout.setString(1, batchId);
                        payout.setString(2, owner.toString());
                        payout.setDouble(3, BankInterestSettlementStore.fromMinor(shareMinor));
                        payout.executeUpdate();
                    }
                }
                try (PreparedStatement retainedUpdate = connection.prepareStatement(
                        "UPDATE ks_bank_risk_state SET retained_earnings=retained_earnings-? WHERE bank_id=?")) {
                    retainedUpdate.setDouble(1, amount);
                    retainedUpdate.setString(2, bankId);
                    if (retainedUpdate.executeUpdate() != 1) throw new SQLException("retained earnings changed");
                }
                connection.commit();
                return success("股东分红已计入本行活期账户", Map.of(
                        "batchId", batchId, "amount", amount, "recipients", shareholders.size()));
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            eco.getLogger().warning("[银行经营] 股东分红失败: " + failure.getMessage());
            return fail("分红失败，全部账务已回滚");
        }
    }

    public List<Map<String, Object>> dividends(String bankId) {
        List<Map<String, Object>> batches = new ArrayList<>();
        if (bankId == null || bankId.isBlank()) return batches;
        try (Connection connection = eco.ksCore().dataStore().getConnection();
             PreparedStatement statement = connection == null ? null : connection.prepareStatement(
                     "SELECT * FROM ks_bank_dividend_batches WHERE bank_id=? ORDER BY declared_at DESC LIMIT 50")) {
            if (statement == null) return batches;
            statement.setString(1, bankId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) batches.add(row(rows));
            }
        } catch (SQLException failure) {
            eco.getLogger().warning("[银行经营] 分红记录读取失败: " + failure.getMessage());
        }
        return batches;
    }

    public Map<String, Object> requestRestructure(UUID borrowerUuid, String loanId, int requestedDays) {
        if (borrowerUuid == null || loanId == null || loanId.isBlank()
                || !List.of(7, 14, 30).contains(requestedDays)) return fail("展期参数无效");
        String requestId = "RR-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT);
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return fail("数据库不可用");
            connection.setAutoCommit(false);
            try {
                String bankId;
                double remaining;
                int restructureCount;
                try (PreparedStatement loan = connection.prepareStatement("""
                        SELECT bank_id,remaining,restructure_count FROM ks_bank_loans
                        WHERE id=? AND borrower_uuid=? AND status IN ('ACTIVE','OVERDUE')
                        """)) {
                    loan.setString(1, loanId);
                    loan.setString(2, borrowerUuid.toString());
                    try (ResultSet row = loan.executeQuery()) {
                        if (!row.next()) {
                            connection.rollback();
                            return fail("贷款不存在或当前不可申请展期");
                        }
                        bankId = row.getString(1);
                        remaining = row.getDouble(2);
                        restructureCount = row.getInt(3);
                    }
                }
                if (restructureCount >= 2) {
                    connection.rollback();
                    return fail("该贷款已达到两次展期上限");
                }
                try (PreparedStatement pending = connection.prepareStatement(
                        "SELECT 1 FROM ks_bank_restructure_requests WHERE loan_id=? AND status='PENDING'")) {
                    pending.setString(1, loanId);
                    if (pending.executeQuery().next()) {
                        connection.rollback();
                        return fail("该贷款已有待审批展期申请");
                    }
                }
                double fee = BankInterestSettlementStore.fromMinor(BankInterestSettlementStore.toMinor(
                        remaining * 0.01 * Math.ceil(requestedDays / 7.0)));
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO ks_bank_restructure_requests
                        (id,loan_id,bank_id,borrower_uuid,requested_days,quoted_fee,status,requested_at)
                        VALUES (?,?,?,?,?,?,'PENDING',?)
                        """)) {
                    insert.setString(1, requestId);
                    insert.setString(2, loanId);
                    insert.setString(3, bankId);
                    insert.setString(4, borrowerUuid.toString());
                    insert.setInt(5, requestedDays);
                    insert.setDouble(6, fee);
                    insert.setLong(7, now());
                    insert.executeUpdate();
                }
                connection.commit();
                return success("展期申请已提交，等待银行审批", Map.of(
                        "id", requestId, "loanId", loanId, "requestedDays", requestedDays, "fee", fee));
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            eco.getLogger().warning("[银行经营] 贷款展期申请失败: " + failure.getMessage());
            return fail("展期申请失败");
        }
    }

    public List<Map<String, Object>> restructureRequests(String bankId, String status) {
        List<Map<String, Object>> requests = new ArrayList<>();
        if (bankId == null || bankId.isBlank()) return requests;
        String normalizedStatus = status == null || status.isBlank() ? null : status.toUpperCase(Locale.ROOT);
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return requests;
            String sql = "SELECT * FROM ks_bank_restructure_requests WHERE bank_id=?"
                    + (normalizedStatus == null ? "" : " AND status=?") + " ORDER BY requested_at DESC LIMIT 100";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, bankId);
                if (normalizedStatus != null) statement.setString(2, normalizedStatus);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) requests.add(row(rows));
                }
            }
        } catch (SQLException failure) {
            eco.getLogger().warning("[银行经营] 展期申请读取失败: " + failure.getMessage());
        }
        return requests;
    }

    public Map<String, Object> decideRestructure(String requestId, UUID decidedBy, boolean approve) {
        if (requestId == null || requestId.isBlank() || decidedBy == null) return fail("审批参数无效");
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return fail("数据库不可用");
            connection.setAutoCommit(false);
            try {
                String loanId;
                int days;
                double fee;
                long priorDueAt;
                try (PreparedStatement request = connection.prepareStatement(
                        "SELECT r.loan_id,r.requested_days,r.quoted_fee,l.due_at FROM ks_bank_restructure_requests r "
                                + "JOIN ks_bank_loans l ON l.id=r.loan_id WHERE r.id=? AND r.status='PENDING'")) {
                    request.setString(1, requestId);
                    try (ResultSet row = request.executeQuery()) {
                        if (!row.next()) {
                            connection.rollback();
                            return fail("展期申请不存在或已经处理");
                        }
                        loanId = row.getString(1);
                        days = row.getInt(2);
                        fee = row.getDouble(3);
                        priorDueAt = row.getLong(4);
                    }
                }
                if (approve) {
                    try (PreparedStatement loan = connection.prepareStatement("""
                            UPDATE ks_bank_loans SET due_at=?,grace_until=?,remaining=remaining+?,
                              restructure_count=restructure_count+1,status='ACTIVE'
                            WHERE id=? AND due_at=? AND status IN ('ACTIVE','OVERDUE') AND restructure_count<2
                            """)) {
                        long extendedDueAt = Math.addExact(priorDueAt, Math.multiplyExact((long) days, DAY));
                        loan.setLong(1, extendedDueAt);
                        loan.setLong(2, extendedDueAt);
                        loan.setDouble(3, fee);
                        loan.setString(4, loanId);
                        loan.setLong(5, priorDueAt);
                        if (loan.executeUpdate() != 1) {
                            connection.rollback();
                            return fail("贷款状态已经变化，无法批准展期");
                        }
                    }
                    try (PreparedStatement schedules = connection.prepareStatement(
                            "DELETE FROM ks_bank_loan_schedules WHERE loan_id=?")) {
                        schedules.setString(1, loanId);
                        schedules.executeUpdate();
                    }
                }
                try (PreparedStatement request = connection.prepareStatement("""
                        UPDATE ks_bank_restructure_requests SET status=?,decided_at=?,decided_by=?
                        WHERE id=? AND status='PENDING'
                        """)) {
                    request.setString(1, approve ? "APPROVED" : "REJECTED");
                    request.setLong(2, now());
                    request.setString(3, decidedBy.toString());
                    request.setString(4, requestId);
                    if (request.executeUpdate() != 1) throw new SQLException("restructure request changed");
                }
                connection.commit();
                return success(approve ? "贷款展期已批准" : "贷款展期已拒绝",
                        Map.of("requestId", requestId, "loanId", loanId, "approved", approve));
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            eco.getLogger().warning("[银行经营] 展期审批失败: " + failure.getMessage());
            return fail("展期审批失败");
        }
    }

    public List<Map<String, Object>> policyEvents() {
        List<Map<String, Object>> events = new ArrayList<>();
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return events;
            maintainPolicyEvents(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM ks_bank_policy_events WHERE status IN ('SCHEDULED','ACTIVE') ORDER BY starts_at,id");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) events.add(row(rows));
            }
        } catch (SQLException failure) {
            eco.getLogger().warning("[银行经营] 政策事件读取失败: " + failure.getMessage());
        }
        return events;
    }

    public Map<String, Object> createPolicyEvent(String eventType, String title, String description,
                                                  double rateModifier, double riskModifier,
                                                  long startsAt, long endsAt, UUID createdBy) {
        String type = eventType == null ? "" : eventType.trim().toUpperCase(Locale.ROOT);
        String safeTitle = title == null ? "" : title.trim();
        String safeDescription = description == null ? "" : description.trim();
        if (!List.of("RATE_CYCLE", "LIQUIDITY", "REAL_ESTATE", "DEFAULT_WAVE", "DEPOSIT_COMPETITION")
                .contains(type) || safeTitle.isBlank() || safeTitle.length() > 128
                || safeDescription.length() > 1024 || !Double.isFinite(rateModifier)
                || rateModifier < -0.25 || rateModifier > 0.25 || !Double.isFinite(riskModifier)
                || riskModifier < -1 || riskModifier > 1 || endsAt <= startsAt || endsAt - startsAt > 90 * DAY) {
            return fail("政策事件参数无效");
        }
        long current = now();
        if (startsAt < current - DAY) return fail("政策事件开始时间无效");
        String id = "PE-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT);
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return fail("数据库不可用");
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO ks_bank_policy_events
                    (id,event_type,title,description,rate_modifier,risk_modifier,starts_at,ends_at,status,created_by,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                statement.setString(1, id);
                statement.setString(2, type);
                statement.setString(3, safeTitle);
                statement.setString(4, safeDescription);
                statement.setDouble(5, rateModifier);
                statement.setDouble(6, riskModifier);
                statement.setLong(7, startsAt);
                statement.setLong(8, endsAt);
                statement.setString(9, startsAt <= current ? "ACTIVE" : "SCHEDULED");
                statement.setString(10, createdBy == null ? null : createdBy.toString());
                statement.setLong(11, current);
                statement.executeUpdate();
            }
            return success("政策事件已创建", Map.of("id", id));
        } catch (SQLException failure) {
            eco.getLogger().warning("[银行经营] 创建政策事件失败: " + failure.getMessage());
            return fail("政策事件创建失败");
        }
    }

    public Map<String, Object> setOperatingStatus(String bankId, String requestedStatus) {
        String status = requestedStatus == null ? "" : requestedStatus.trim().toUpperCase(Locale.ROOT);
        if (bankId == null || bankId.isBlank()
                || !List.of("NORMAL", "WATCH", "RESTRICTED", "RESOLUTION").contains(status)) {
            return fail("银行或处置状态无效");
        }
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return fail("数据库不可用");
            long timestamp = now();
            BankSqlMutation.upsert(connection,
                    "UPDATE ks_bank_risk_state SET operating_status=?,last_assessed_at=? WHERE bank_id=?", update -> {
                        update.setString(1, status);
                        update.setLong(2, timestamp);
                        update.setString(3, bankId);
                    }, "INSERT INTO ks_bank_risk_state "
                            + "(bank_id,retained_earnings,loan_loss_provision,insured_deposits,liquidity_support,risk_rating,operating_status,last_assessed_at) "
                            + "SELECT id,0,0,0,0,'C',?,? FROM ks_bank_banks WHERE id=?", insert -> {
                        insert.setString(1, status);
                        insert.setLong(2, timestamp);
                        insert.setString(3, bankId);
                    });
            return success("银行处置状态已更新", Map.of("bankId", bankId, "operatingStatus", status));
        } catch (SQLException failure) {
            eco.getLogger().warning("[银行经营] 更新处置状态失败: " + failure.getMessage());
            return fail("处置状态更新失败");
        }
    }

    public List<Map<String, Object>> loanProducts() {
        return List.of(
                loanProduct("CONSUMER", "消费信用贷", 100, 20_000, 1, 30, 1.35, false,
                        "无抵押，审批快，适合短期消费周转"),
                loanProduct("HOME", "住房抵押贷", 5_000, 50_000, 30, 90, 0.72, true,
                        "绑定登记房产，期限长、利率较低"),
                loanProduct("BUSINESS", "经营周转贷", 1_000, 50_000, 7, 90, 0.95, true,
                        "面向企业经营与库存周转"),
                loanProduct("PROJECT", "项目履约贷", 1_000, 50_000, 7, 90, 0.82, true,
                        "绑定已中标工程合同和预期回款"));
    }

    public Map<String, Object> productLoanQuote(String bankId, UUID borrowerUuid, double principal,
                                                int termDays, String productType, String repaymentType) {
        return productLoanQuote(bankId, borrowerUuid, principal, termDays, productType, repaymentType, "", "");
    }

    public List<Map<String, Object>> eligibleCollateral(UUID borrowerUuid, String productType) {
        if (borrowerUuid == null) return List.of();
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return List.of();
            return PlayerLoanCollateralStore.eligible(connection, borrowerUuid, productType);
        } catch (SQLException failure) {
            eco.getLogger().warning("[银行经营] 查询可抵押资产失败: " + failure.getMessage());
            return List.of();
        }
    }

    public Map<String, Object> productLoanQuote(String bankId, UUID borrowerUuid, double principal,
                                                int termDays, String productType, String repaymentType,
                                                String collateralType, String collateralRef) {
        String product = productType == null ? "" : productType.trim().toUpperCase(Locale.ROOT);
        String repayment = repaymentType == null ? "" : repaymentType.trim().toUpperCase(Locale.ROOT);
        Map<String, Object> definition = loanProducts().stream()
                .filter(item -> product.equals(item.get("code"))).findFirst().orElse(null);
        if (definition == null) return fail("贷款产品不存在");
        if (!List.of("BULLET", "EQUAL_PAYMENT", "EQUAL_PRINCIPAL").contains(repayment)) {
            return fail("还款方式无效");
        }
        double minimum = ((Number) definition.get("minAmount")).doubleValue();
        double maximum = ((Number) definition.get("maxAmount")).doubleValue();
        int minDays = ((Number) definition.get("minTermDays")).intValue();
        int maxDays = ((Number) definition.get("maxTermDays")).intValue();
        if (principal < minimum || principal > maximum || termDays < minDays || termDays > maxDays) {
            return fail("金额或期限不符合所选产品范围");
        }
        PlayerLoanCollateralStore.Appraisal appraisal = null;
        if (Boolean.TRUE.equals(definition.get("collateralRequired"))) {
            try (Connection connection = eco.ksCore().dataStore().getConnection()) {
                if (connection == null) return fail("数据库不可用");
                appraisal = PlayerLoanCollateralStore.appraise(connection, borrowerUuid, product,
                        collateralType, collateralRef);
            } catch (SQLException failure) {
                return fail("抵押物估值失败");
            }
            if (appraisal == null) return fail("请选择属于你的、未被占用的合规抵押物");
            if (principal > appraisal.maxLoan() + 0.000_001) return fail("贷款金额超过抵押物可贷额度");
        }
        Map<String, Object> base = bankManager.loanQuote(bankId, borrowerUuid, principal, termDays);
        if (Boolean.FALSE.equals(base.get("available")) || Boolean.FALSE.equals(base.get("allowed"))) return base;
        double factor = ((Number) definition.get("rateFactor")).doubleValue();
        double policyModifier = activePolicyRateModifier();
        double effectiveRate = Math.max(0, Math.min(1,
                ((Number) base.getOrDefault("effectiveRate", 0)).doubleValue() * factor + policyModifier));
        Map<String, Object> result = new LinkedHashMap<>(base);
        result.put("success", true);
        result.put("productType", product);
        result.put("productName", definition.get("name"));
        result.put("repaymentType", repayment);
        result.put("effectiveRate", effectiveRate);
        result.put("totalDue", principal * (1 + effectiveRate));
        result.put("rateFactor", factor);
        result.put("policyRateModifier", policyModifier);
        result.put("quoteLocked", true);
        result.put("installments", "BULLET".equals(repayment) ? 1 : Math.max(1, Math.min(12, (int) Math.ceil(termDays / 7.0))));
        if (appraisal != null) {
            result.put("collateralType", appraisal.assetType());
            result.put("collateralRef", appraisal.assetRef());
            result.put("appraisedValue", appraisal.value());
            result.put("loanToValue", appraisal.loanToValue());
            result.put("maxLoan", appraisal.maxLoan());
        }
        return result;
    }

    private void settleMaturedDeposits(UUID owner) {
        List<TermDeposit> due = new ArrayList<>();
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return;
            String sql = "SELECT * FROM ks_bank_term_deposits WHERE status='ACTIVE' AND matures_at<=?"
                    + (owner == null ? "" : " AND player_uuid=?") + " ORDER BY matures_at,id LIMIT 500";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, now());
                if (owner != null) statement.setString(2, owner.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) due.add(termDeposit(rows));
                }
            }
        } catch (SQLException failure) {
            eco.getLogger().warning("[银行经营] 到期存单扫描失败: " + failure.getMessage());
            return;
        }
        for (TermDeposit deposit : due) settleMaturedDeposit(deposit);
    }

    private void maintainPolicyEvents(Connection connection) throws SQLException {
        long current = now();
        try (PreparedStatement activate = connection.prepareStatement(
                "UPDATE ks_bank_policy_events SET status='ACTIVE' WHERE status='SCHEDULED' AND starts_at<=? AND ends_at>?")) {
            activate.setLong(1, current);
            activate.setLong(2, current);
            activate.executeUpdate();
        }
        try (PreparedStatement end = connection.prepareStatement(
                "UPDATE ks_bank_policy_events SET status='ENDED' WHERE status IN ('SCHEDULED','ACTIVE') AND ends_at<=?")) {
            end.setLong(1, current);
            end.executeUpdate();
        }
    }

    private double activePolicyRateModifier() {
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return 0;
            maintainPolicyEvents(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COALESCE(SUM(rate_modifier),0) FROM ks_bank_policy_events WHERE status='ACTIVE' AND starts_at<=? AND ends_at>?")) {
                long current = now();
                statement.setLong(1, current);
                statement.setLong(2, current);
                try (ResultSet row = statement.executeQuery()) {
                    return row.next() ? Math.max(-0.25, Math.min(0.25, row.getDouble(1))) : 0;
                }
            }
        } catch (SQLException failure) {
            return 0;
        }
    }

    private void ensureLoanSchedule(Connection connection, Map<String, Object> loan) throws SQLException {
        String id = String.valueOf(loan.get("id"));
        String status = String.valueOf(loan.get("status"));
        if (!("ACTIVE".equals(status) || "OVERDUE".equals(status) || "PAID".equals(status))) return;
        if ("PAID".equals(status)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ks_bank_loan_schedules SET status='PAID',paid_amount=principal_due+interest_due,"
                            + "paid_at=COALESCE(paid_at,?) WHERE loan_id=? AND status<>'PAID'")) {
                statement.setLong(1, now());
                statement.setString(2, id);
                statement.executeUpdate();
            }
            return;
        }
        try (PreparedStatement exists = connection.prepareStatement(
                "SELECT 1 FROM ks_bank_loan_schedules WHERE loan_id=? LIMIT 1")) {
            exists.setString(1, id);
            if (exists.executeQuery().next()) return;
        }
        double principal = number(loan.get("principal"));
        double totalDue = number(loan.get("remaining"));
        long issuedAt = longNumber(loan.get("issued_at"));
        long dueAt = longNumber(loan.get("due_at"));
        String repayment = String.valueOf(loan.getOrDefault("repayment_type", "BULLET"));
        int termDays = Math.max(1, (int) Math.ceil((dueAt - issuedAt) / (double) DAY));
        int installments = "BULLET".equals(repayment) ? 1 : Math.max(1, Math.min(12, (int) Math.ceil(termDays / 7.0)));
        double interest = Math.max(0, totalDue - principal);
        for (int index = 1; index <= installments; index++) {
            double principalDue;
            if ("EQUAL_PRINCIPAL".equals(repayment)) principalDue = principal / installments;
            else principalDue = principal / installments;
            double interestDue = "EQUAL_PRINCIPAL".equals(repayment)
                    ? interest * (installments - index + 1) / (installments * (installments + 1) / 2.0)
                    : interest / installments;
            long installmentDue = issuedAt + Math.max(1, (dueAt - issuedAt) * index / installments);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO ks_bank_loan_schedules
                    (loan_id,installment_no,due_at,principal_due,interest_due,paid_amount,status)
                    VALUES (?,?,?,?,?,0,'PENDING')
                    """)) {
                insert.setString(1, id);
                insert.setInt(2, index);
                insert.setLong(3, installmentDue);
                insert.setDouble(4, principalDue);
                insert.setDouble(5, interestDue);
                insert.executeUpdate();
            }
        }
    }

    private void settleMaturedDeposit(TermDeposit deposit) {
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return;
            connection.setAutoCommit(false);
            try {
                double interest = BankInterestSettlementStore.fromMinor(
                        BankInterestSettlementStore.toMinor(deposit.principal() * deposit.fixedRate()));
                long current = now();
                if (deposit.autoRenew()) {
                    double renewedPrincipal = BankInterestSettlementStore.fromMinor(
                            BankInterestSettlementStore.toMinor(deposit.principal() + interest));
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE ks_bank_term_deposits
                            SET principal=?,opened_at=?,matures_at=?,accrued_interest=accrued_interest+?,version=version+1
                            WHERE id=? AND status='ACTIVE' AND version=? AND matures_at<=?
                            """)) {
                        statement.setDouble(1, renewedPrincipal);
                        statement.setLong(2, current);
                        statement.setLong(3, current + deposit.termDays() * DAY);
                        statement.setDouble(4, interest);
                        statement.setString(5, deposit.id());
                        statement.setLong(6, deposit.version());
                        statement.setLong(7, current);
                        statement.executeUpdate();
                    }
                } else {
                    long payoutMinor = BankInterestSettlementStore.toMinor(deposit.principal() + interest);
                    BankInterestSettlementStore.SettlementResult mutation = bankManager.mutateAccountBalance(
                            connection, deposit.bankId(), deposit.bankId() + ":" + deposit.playerUuid(),
                            deposit.playerUuid(), current, payoutMinor, true);
                    if (!mutation.applied()) {
                        connection.rollback();
                        return;
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE ks_bank_term_deposits
                            SET status='MATURED',accrued_interest=?,redeemed_at=?,version=version+1
                            WHERE id=? AND status='ACTIVE' AND version=? AND matures_at<=?
                            """)) {
                        statement.setDouble(1, interest);
                        statement.setLong(2, current);
                        statement.setString(3, deposit.id());
                        statement.setLong(4, deposit.version());
                        statement.setLong(5, current);
                        if (statement.executeUpdate() != 1) {
                            connection.rollback();
                            return;
                        }
                    }
                }
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            eco.getLogger().warning("[银行经营] 到期存单结算失败 " + deposit.id() + ": " + failure.getMessage());
        }
    }

    private void seedProducts(Connection connection) throws SQLException {
        List<String> banks = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM ks_bank_banks WHERE status='ACTIVE' AND type<>'CENTRAL'");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) banks.add(rows.getString(1));
        }
        for (String bankId : banks) seedProducts(connection, bankId);
    }

    private void seedProducts(Connection connection, String bankId) throws SQLException {
        double baseRate;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT interest_rate FROM ks_bank_banks WHERE id=? AND status='ACTIVE'")) {
            statement.setString(1, bankId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return;
                baseRate = Math.max(0, row.getDouble(1));
            }
        }
        addProduct(connection, bankId, "TERM_7", "七日稳盈", 7, Math.max(0.002, baseRate * 0.35), 100, 1.0);
        addProduct(connection, bankId, "TERM_30", "三十日定期", 30, Math.max(0.006, baseRate * 0.75), 500, 0.75);
        addProduct(connection, bankId, "TERM_90", "九十日增益", 90, Math.max(0.015, baseRate * 1.50), 1_000, 0.60);
        addProduct(connection, bankId, "CD_180", "一百八十日大额存单", 180, Math.max(0.030, baseRate * 2.50), 50_000, 0.50);
    }

    private void addProduct(Connection connection, String bankId, String code, String name, int termDays,
                            double fixedRate, double minimum, double earlyPenalty) throws SQLException {
        long timestamp = now();
        BankSqlMutation.insertIfAbsent(connection,
                "SELECT 1 FROM ks_bank_deposit_products WHERE bank_id=? AND product_code=?", exists -> {
                    exists.setString(1, bankId);
                    exists.setString(2, code);
                }, "INSERT INTO ks_bank_deposit_products "
                        + "(bank_id,product_code,name,term_days,fixed_rate,min_amount,early_penalty_rate,active,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,1,?,?)", insert -> {
                    insert.setString(1, bankId);
                    insert.setString(2, code);
                    insert.setString(3, name);
                    insert.setInt(4, termDays);
                    insert.setDouble(5, fixedRate);
                    insert.setDouble(6, minimum);
                    insert.setDouble(7, earlyPenalty);
                    insert.setLong(8, timestamp);
                    insert.setLong(9, timestamp);
                });
    }

    private void assessAllBanks(Connection connection) throws SQLException {
        List<String> banks = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM ks_bank_banks WHERE type<>'CENTRAL'"); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) banks.add(rows.getString(1));
        }
        for (String bankId : banks) assessBank(connection, bankId, false);
    }

    private Map<String, Object> assessBank(Connection connection, String bankId, boolean includeProducts)
            throws SQLException {
        String name;
        String status;
        double liquidity;
        double reserveRatio;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name,status,total_assets,reserve_ratio FROM ks_bank_banks WHERE id=?")) {
            statement.setString(1, bankId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return fail("银行不存在");
                name = row.getString("name");
                status = row.getString("status");
                liquidity = row.getDouble("total_assets");
                reserveRatio = row.getDouble("reserve_ratio");
            }
        }
        double demand = scalar(connection,
                "SELECT COALESCE(SUM(balance),0) FROM ks_bank_accounts WHERE bank_id=?", bankId);
        double terms = scalar(connection,
                "SELECT COALESCE(SUM(principal),0) FROM ks_bank_term_deposits WHERE bank_id=? AND status='ACTIVE'", bankId);
        double loans = scalar(connection,
                "SELECT COALESCE(SUM(remaining),0) FROM ks_bank_loans WHERE bank_id=? AND status IN ('ACTIVE','OVERDUE')", bankId)
                + scalar(connection,
                "SELECT COALESCE(SUM(remaining),0) FROM ks_bank_enterprise_loans WHERE bank_id=? AND status IN ('ACTIVE','OVERDUE')", bankId);
        double overdue = scalar(connection,
                "SELECT COALESCE(SUM(remaining),0) FROM ks_bank_loans WHERE bank_id=? AND status='OVERDUE'", bankId)
                + scalar(connection,
                "SELECT COALESCE(SUM(remaining),0) FROM ks_bank_enterprise_loans WHERE bank_id=? AND status='OVERDUE'", bankId);
        double realizedLoanInterest = scalar(connection,
                "SELECT COALESCE(SUM(principal*interest_rate),0) FROM ks_bank_loans WHERE bank_id=? AND status='PAID'", bankId)
                + scalar(connection,
                "SELECT COALESCE(SUM(principal*interest_rate),0) FROM ks_bank_enterprise_loans WHERE bank_id=? AND status='PAID'", bankId);
        double demandInterestExpense = scalar(connection,
                "SELECT COALESCE(SUM(interest_earned),0) FROM ks_bank_accounts WHERE bank_id=?", bankId);
        double termInterestExpense = scalar(connection,
                "SELECT COALESCE(SUM(accrued_interest),0) FROM ks_bank_term_deposits WHERE bank_id=?", bankId);
        double dividendsPaid = scalar(connection,
                "SELECT COALESCE(SUM(amount),0) FROM ks_bank_dividend_batches WHERE bank_id=? AND status='FINALIZED'", bankId);
        double deposits = demand + terms;
        double insuredDeposits = insuredDeposits(connection, bankId, 100_000);
        double totalAssets = liquidity + loans;
        double equity = totalAssets - deposits;
        double liquidityRatio = deposits <= 0 ? 1 : liquidity / deposits;
        double capitalRatio = totalAssets <= 0 ? 1 : equity / totalAssets;
        double badDebtRatio = loans <= 0 ? 0 : overdue / loans;
        double policyRiskModifier = activePolicyRiskModifier(connection);
        double assessedBadDebtRatio = Math.max(0, Math.min(1, badDebtRatio + policyRiskModifier));
        double interestExpense = demandInterestExpense + termInterestExpense;
        double provision = overdue * 0.50;
        double retainedEarnings = realizedLoanInterest - interestExpense - provision - dividendsPaid;
        String previousOperating = operatingStatus(connection, bankId);
        String rating;
        String operating;
        if (capitalRatio < 0 || liquidityRatio < 0.03) {
            rating = "E";
            operating = "RESOLUTION";
        } else if (capitalRatio < 0.06 || liquidityRatio < 0.08 || assessedBadDebtRatio > 0.25) {
            rating = "D";
            operating = "RESTRICTED";
        } else if (capitalRatio < 0.10 || liquidityRatio < 0.12 || assessedBadDebtRatio > 0.12) {
            rating = "C";
            operating = "WATCH";
        } else if (capitalRatio < 0.14 || liquidityRatio < 0.20 || assessedBadDebtRatio > 0.05) {
            rating = "B";
            operating = "NORMAL";
        } else {
            rating = "A";
            operating = "NORMAL";
        }
        if ("RESOLUTION".equals(previousOperating)) {
            rating = "E";
            operating = "RESOLUTION";
        } else if ("RESTRICTED".equals(previousOperating) && "NORMAL".equals(operating)) {
            operating = "RESTRICTED";
        }
        final String finalRating = rating;
        final String finalOperating = operating;
        long assessedAt = now();
        BankSqlMutation.upsert(connection,
                "UPDATE ks_bank_risk_state SET retained_earnings=?,insured_deposits=?,loan_loss_provision=?,risk_rating=?,"
                        + "operating_status=?,last_assessed_at=? WHERE bank_id=?", update -> {
                    update.setDouble(1, retainedEarnings);
                    update.setDouble(2, insuredDeposits);
                    update.setDouble(3, provision);
                    update.setString(4, finalRating);
                    update.setString(5, finalOperating);
                    update.setLong(6, assessedAt);
                    update.setString(7, bankId);
                }, "INSERT INTO ks_bank_risk_state "
                        + "(bank_id,retained_earnings,loan_loss_provision,insured_deposits,liquidity_support,risk_rating,operating_status,last_assessed_at) "
                        + "VALUES (?,?,?,?,0,?,?,?)", insert -> {
                    insert.setString(1, bankId);
                    insert.setDouble(2, retainedEarnings);
                    insert.setDouble(3, provision);
                    insert.setDouble(4, insuredDeposits);
                    insert.setString(5, finalRating);
                    insert.setString(6, finalOperating);
                    insert.setLong(7, assessedAt);
                });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("bankId", bankId);
        result.put("name", name);
        result.put("status", status);
        result.put("liquidity", liquidity);
        result.put("demandDeposits", demand);
        result.put("termDeposits", terms);
        result.put("totalDeposits", deposits);
        result.put("insuredDeposits", insuredDeposits);
        result.put("loanAssets", loans);
        result.put("overdueLoans", overdue);
        result.put("totalAssets", totalAssets);
        result.put("equity", equity);
        result.put("reserveRatio", reserveRatio);
        result.put("liquidityRatio", liquidityRatio);
        result.put("capitalRatio", capitalRatio);
        result.put("badDebtRatio", badDebtRatio);
        result.put("policyRiskModifier", policyRiskModifier);
        result.put("assessedBadDebtRatio", assessedBadDebtRatio);
        result.put("realizedLoanInterest", realizedLoanInterest);
        result.put("interestExpense", interestExpense);
        result.put("loanLossProvision", provision);
        result.put("dividendsPaid", dividendsPaid);
        result.put("retainedEarnings", retainedEarnings);
        result.put("netInterestMargin", loans <= 0 ? 0 : (realizedLoanInterest - interestExpense) / loans);
        result.put("rating", finalRating);
        result.put("operatingStatus", finalOperating);
        result.put("assessedAt", assessedAt);
        if (includeProducts) result.put("depositProducts", depositProducts(bankId));
        return result;
    }

    private String operatingStatus(Connection connection, String bankId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT operating_status FROM ks_bank_risk_state WHERE bank_id=?")) {
            statement.setString(1, bankId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getString(1) : "NORMAL";
            }
        }
    }

    private double activePolicyRiskModifier(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(SUM(risk_modifier),0) FROM ks_bank_policy_events "
                        + "WHERE status='ACTIVE' AND starts_at<=? AND ends_at>?")) {
            long current = now();
            statement.setLong(1, current);
            statement.setLong(2, current);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Math.max(-1, Math.min(1, row.getDouble(1))) : 0;
            }
        }
    }

    private List<UUID> bankOwners(Connection connection, String bankId) throws SQLException {
        List<UUID> owners = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT owner_uuids FROM ks_bank_banks WHERE id=? AND type='COMMERCIAL'")) {
            statement.setString(1, bankId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return owners;
                for (String value : row.getString(1).split(",")) {
                    try {
                        UUID owner = UUID.fromString(value.trim());
                        if (!owners.contains(owner)) owners.add(owner);
                    } catch (IllegalArgumentException ignored) {
                        // System and malformed legacy identities cannot receive a player dividend.
                    }
                }
            }
        }
        return owners;
    }

    private Product findProduct(Connection connection, String bankId, String code) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT product_code,name,term_days,fixed_rate,min_amount,early_penalty_rate,active
                FROM ks_bank_deposit_products WHERE bank_id=? AND product_code=?
                """)) {
            statement.setString(1, bankId);
            statement.setString(2, code.toUpperCase(Locale.ROOT));
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? new Product(row.getString(1), row.getString(2), row.getInt(3),
                        row.getDouble(4), row.getDouble(5), row.getDouble(6), row.getInt(7) != 0) : null;
            }
        }
    }

    private TermDeposit findTermDeposit(Connection connection, String id, UUID owner) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM ks_bank_term_deposits WHERE id=? AND player_uuid=?")) {
            statement.setString(1, id);
            statement.setString(2, owner.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? termDeposit(row) : null;
            }
        }
    }

    private TermDeposit termDeposit(ResultSet row) throws SQLException {
        return new TermDeposit(row.getString("id"), row.getString("bank_id"),
                UUID.fromString(row.getString("player_uuid")), row.getString("product_code"),
                row.getDouble("principal"), row.getDouble("fixed_rate"), row.getInt("term_days"),
                row.getLong("opened_at"), row.getLong("matures_at"), row.getInt("auto_renew") != 0,
                row.getString("status"), row.getLong("version"));
    }

    private double scalar(Connection connection, String sql, String bankId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, bankId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getDouble(1) : 0;
            }
        }
    }

    private double insuredDeposits(Connection connection, String bankId, double coverageLimit) throws SQLException {
        Map<String, Double> balances = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid,balance FROM ks_bank_accounts WHERE bank_id=? AND balance>0")) {
            statement.setString(1, bankId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) balances.merge(rows.getString(1), rows.getDouble(2), Double::sum);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid,principal+accrued_interest FROM ks_bank_term_deposits WHERE bank_id=? AND status='ACTIVE'")) {
            statement.setString(1, bankId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) balances.merge(rows.getString(1), rows.getDouble(2), Double::sum);
            }
        }
        return balances.values().stream().mapToDouble(value -> Math.min(Math.max(0, value), coverageLimit)).sum();
    }

    private Map<String, Object> row(ResultSet row) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        ResultSetMetaData meta = row.getMetaData();
        for (int index = 1; index <= meta.getColumnCount(); index++) {
            result.put(meta.getColumnLabel(index), row.getObject(index));
        }
        return result;
    }

    private Map<String, Object> loanProduct(String code, String name, double minimum, double maximum,
                                            int minDays, int maxDays, double rateFactor,
                                            boolean collateralRequired, String description) {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("code", code);
        product.put("name", name);
        product.put("minAmount", minimum);
        product.put("maxAmount", maximum);
        product.put("minTermDays", minDays);
        product.put("maxTermDays", maxDays);
        product.put("rateFactor", rateFactor);
        product.put("collateralRequired", collateralRequired);
        product.put("description", description);
        return product;
    }

    private Map<String, Object> success(String message, Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>(values);
        result.put("success", true);
        result.put("message", message);
        return result;
    }

    private Map<String, Object> fail(String reason) {
        return Map.of("success", false, "error", reason);
    }

    private long now() {
        return System.currentTimeMillis() / 1000;
    }

    static TermPayout calculateTermPayout(double principal, double fixedRate, double earlyPenaltyRate,
                                          long openedAt, long maturesAt, long current) {
        if (!Double.isFinite(principal) || principal <= 0 || !Double.isFinite(fixedRate) || fixedRate < 0
                || !Double.isFinite(earlyPenaltyRate) || earlyPenaltyRate < 0 || earlyPenaltyRate > 1
                || maturesAt <= openedAt) throw new IllegalArgumentException("invalid term deposit");
        boolean matured = current >= maturesAt;
        double elapsed = Math.max(0, Math.min(1,
                (current - openedAt) / (double) Math.max(1, maturesAt - openedAt)));
        double grossInterest = principal * fixedRate * (matured ? 1 : elapsed);
        double interest = matured ? grossInterest : grossInterest * (1 - earlyPenaltyRate);
        interest = BankInterestSettlementStore.fromMinor(BankInterestSettlementStore.toMinor(interest));
        double payout = BankInterestSettlementStore.fromMinor(
                BankInterestSettlementStore.toMinor(principal + interest));
        return new TermPayout(matured, interest, payout);
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value));
    }

    private long longNumber(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private record Product(String code, String name, int termDays, double fixedRate,
                           double minAmount, double earlyPenaltyRate, boolean active) {}

    private record TermDeposit(String id, String bankId, UUID playerUuid, String productCode,
                               double principal, double fixedRate, int termDays, long openedAt,
                               long maturesAt, boolean autoRenew, String status, long version) {}

    record TermPayout(boolean matured, double interest, double payout) {}
}
