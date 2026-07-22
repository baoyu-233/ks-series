package org.kseco.extra.bank;

import org.kseco.KsEco;
import org.kseco.database.BusinessSchemaDialect;

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
import java.util.UUID;

/** Deposit-insurance premiums, loss waterfall and atomic bridge-bank resolution. */
public final class BankResolutionManager {
    private static final String FUND_ID = "DEFAULT";
    private static final double DEFAULT_COVERAGE = 100_000.0;
    private static final double DEFAULT_PREMIUM_RATE = 0.0005;
    private static final long PREMIUM_PERIOD = 30L * 86400L;
    private final KsEco eco;
    private final BankManager bankManager;

    public BankResolutionManager(KsEco eco, BankManager bankManager) {
        this.eco = eco;
        this.bankManager = bankManager;
    }

    public void init() {
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return;
            initialize(connection);
        } catch (SQLException failure) {
            throw new IllegalStateException("Bank resolution schema initialization failed", failure);
        }
    }

    static void initialize(Connection connection) throws SQLException {
        String number = BusinessSchemaDialect.floatingPointType(BusinessSchemaDialect.detect(connection));
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_insurance_fund ("
                    + "id VARCHAR(32) PRIMARY KEY,balance " + number + " NOT NULL,coverage_limit " + number
                    + " NOT NULL,premium_rate " + number + " NOT NULL,updated_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_insurance_membership ("
                    + "bank_id VARCHAR(128) PRIMARY KEY,last_premium_at BIGINT NOT NULL DEFAULT 0,"
                    + "total_premiums " + number + " NOT NULL DEFAULT 0,status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE')");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_resolution_cases ("
                    + "id VARCHAR(128) PRIMARY KEY,bank_id VARCHAR(128) NOT NULL UNIQUE,bridge_bank_id VARCHAR(128) NOT NULL,"
                    + "total_deposits " + number + " NOT NULL,estate_value " + number + " NOT NULL,recovery_ratio "
                    + number + " NOT NULL,insurance_subsidy " + number + " NOT NULL,status VARCHAR(32) NOT NULL,"
                    + "resolved_by VARCHAR(36) NOT NULL,resolved_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_resolution_claims ("
                    + "case_id VARCHAR(128) NOT NULL,player_uuid VARCHAR(36) NOT NULL,original_deposit " + number
                    + " NOT NULL,insured_amount " + number + " NOT NULL,uninsured_amount " + number
                    + " NOT NULL,uninsured_recovery " + number + " NOT NULL,haircut_amount " + number
                    + " NOT NULL,payout_amount " + number + " NOT NULL,bridge_account_id VARCHAR(191) NOT NULL,"
                    + "PRIMARY KEY(case_id,player_uuid))");
        }
        BankSqlMutation.insertIfAbsent(connection,
                "SELECT 1 FROM ks_bank_insurance_fund WHERE id=?", exists -> exists.setString(1, FUND_ID),
                "INSERT INTO ks_bank_insurance_fund(id,balance,coverage_limit,premium_rate,updated_at) VALUES(?,?,?,?,?)",
                insert -> { insert.setString(1, FUND_ID); insert.setDouble(2, 10_000_000);
                    insert.setDouble(3, DEFAULT_COVERAGE); insert.setDouble(4, DEFAULT_PREMIUM_RATE);
                    insert.setLong(5, now()); });
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_bank_resolution_bridge",
                "ks_bank_resolution_cases", "bridge_bank_id", "resolved_at");
    }

    public Map<String, Object> fundStatus() {
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return fail("数据库不可用");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT balance,coverage_limit,premium_rate,updated_at FROM ks_bank_insurance_fund WHERE id=?")) {
                statement.setString(1, FUND_ID);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) return fail("存款保险基金未初始化");
                    return success("存款保险基金状态已加载", Map.of("balance", row.getDouble(1),
                            "coverageLimit", row.getDouble(2), "premiumRate", row.getDouble(3),
                            "updatedAt", row.getLong(4)));
                }
            }
        } catch (SQLException failure) { return fail("保险基金读取失败"); }
    }

    public List<Map<String, Object>> cases() {
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return List.of();
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT c.*,COUNT(k.player_uuid) AS claimant_count,COALESCE(SUM(k.payout_amount),0) AS total_payout,
                           COALESCE(SUM(k.haircut_amount),0) AS total_haircut
                    FROM ks_bank_resolution_cases c LEFT JOIN ks_bank_resolution_claims k ON k.case_id=c.id
                    GROUP BY c.id,c.bank_id,c.bridge_bank_id,c.total_deposits,c.estate_value,c.recovery_ratio,
                             c.insurance_subsidy,c.status,c.resolved_by,c.resolved_at
                    ORDER BY c.resolved_at DESC LIMIT 100
                    """)) {
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        var meta = rows.getMetaData();
                        for (int i = 1; i <= meta.getColumnCount(); i++) row.put(meta.getColumnLabel(i), rows.getObject(i));
                        out.add(row);
                    }
                }
            }
            return List.copyOf(out);
        } catch (SQLException failure) { return List.of(); }
    }

    public Map<String, Object> recapitalizeFund(double amount) {
        if (!Double.isFinite(amount) || amount <= 0 || amount > 1_000_000_000) return fail("注资金额无效");
        double settled;
        try { settled = BankInterestSettlementStore.fromMinor(BankInterestSettlementStore.toMinor(amount)); }
        catch (IllegalArgumentException | ArithmeticException invalid) { return fail("注资金额无效"); }
        try (Connection connection = eco.ksCore().dataStore().getConnection();
             PreparedStatement statement = connection == null ? null : connection.prepareStatement(
                     "UPDATE ks_bank_insurance_fund SET balance=balance+?,updated_at=? WHERE id=?")) {
            if (statement == null) return fail("数据库不可用");
            statement.setDouble(1, settled); statement.setLong(2, now()); statement.setString(3, FUND_ID);
            if (statement.executeUpdate() != 1) return fail("存款保险基金不存在");
            return success("央行已向存款保险基金注资", Map.of("amount", settled));
        } catch (SQLException failure) { return fail("保险基金注资失败"); }
    }

    public Map<String, Object> preview(String bankId, String bridgeBankId) {
        if (bankId == null || bankId.isBlank() || bridgeBankId == null || bridgeBankId.isBlank()
                || bankId.equals(bridgeBankId)) return fail("接管参数无效");
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return fail("数据库不可用");
            return resolutionPreview(connection, bankId, bridgeBankId, true);
        } catch (SQLException failure) { return fail("处置测算失败: " + failure.getMessage()); }
    }

    public Map<String, Object> resolve(String bankId, String bridgeBankId, UUID administrator) {
        if (administrator == null) return fail("管理员身份无效");
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return fail("数据库不可用");
            connection.setAutoCommit(false);
            try {
                Map<String, Object> preview = resolutionPreview(connection, bankId, bridgeBankId, true);
                if (!Boolean.TRUE.equals(preview.get("success"))) { connection.rollback(); return preview; }
                String operatingStatus = String.valueOf(preview.get("operatingStatus"));
                if (!"RESOLUTION".equals(operatingStatus)) {
                    connection.rollback(); return fail("银行尚未进入处置状态，禁止强制清算");
                }
                double subsidy = number(preview.get("insuranceSubsidy"));
                try (PreparedStatement fund = connection.prepareStatement("""
                        UPDATE ks_bank_insurance_fund SET balance=balance-?,updated_at=?
                        WHERE id=? AND balance>=?
                        """)) {
                    fund.setDouble(1, subsidy); fund.setLong(2, now()); fund.setString(3, FUND_ID); fund.setDouble(4, subsidy);
                    if (fund.executeUpdate() != 1) { connection.rollback(); return fail("存款保险基金余额不足"); }
                }
                String caseId = "BR-" + shortId();
                double recoveryRatio = number(preview.get("recoveryRatio"));
                double coverage = number(preview.get("coverageLimit"));
                List<Depositor> depositors = loadDepositors(connection, bankId);
                for (Depositor depositor : depositors) {
                    ClaimOutcome outcome = calculateClaim(depositor.amount(), coverage, recoveryRatio);
                    long payoutMinor = BankInterestSettlementStore.toMinor(outcome.payout());
                    BankInterestSettlementStore.SettlementResult credit = bankManager.mutateAccountBalance(connection,
                            bridgeBankId, bridgeBankId + ":" + depositor.playerUuid(), depositor.playerUuid(),
                            now(), payoutMinor, true);
                    if (!credit.applied()) throw new SQLException("bridge account credit rejected");
                    try (PreparedStatement claim = connection.prepareStatement("""
                            INSERT INTO ks_bank_resolution_claims
                            (case_id,player_uuid,original_deposit,insured_amount,uninsured_amount,uninsured_recovery,
                             haircut_amount,payout_amount,bridge_account_id) VALUES (?,?,?,?,?,?,?,?,?)
                            """)) {
                        claim.setString(1, caseId); claim.setString(2, depositor.playerUuid().toString());
                        claim.setDouble(3, depositor.amount()); claim.setDouble(4, outcome.insured());
                        claim.setDouble(5, outcome.uninsured()); claim.setDouble(6, outcome.uninsuredRecovery());
                        claim.setDouble(7, outcome.haircut()); claim.setDouble(8, outcome.payout());
                        claim.setString(9, bridgeBankId + ":" + depositor.playerUuid()); claim.executeUpdate();
                    }
                }
                try (PreparedStatement accounts = connection.prepareStatement(
                        "UPDATE ks_bank_accounts SET balance=0 WHERE bank_id=? AND balance<>0")) {
                    accounts.setString(1, bankId); accounts.executeUpdate();
                }
                try (PreparedStatement terms = connection.prepareStatement(
                        "UPDATE ks_bank_term_deposits SET status='RESOLVED',redeemed_at=?,version=version+1 WHERE bank_id=? AND status='ACTIVE'")) {
                    terms.setLong(1, now()); terms.setString(2, bankId); terms.executeUpdate();
                }
                releasePendingApplications(connection, bankId);
                transferEstate(connection, bankId, bridgeBankId, subsidy,
                        number(preview.get("liquidity")));
                try (PreparedStatement cases = connection.prepareStatement("""
                        INSERT INTO ks_bank_resolution_cases
                        (id,bank_id,bridge_bank_id,total_deposits,estate_value,recovery_ratio,insurance_subsidy,status,resolved_by,resolved_at)
                        VALUES (?,?,?,?,?,?,?,'FINALIZED',?,?)
                        """)) {
                    cases.setString(1, caseId); cases.setString(2, bankId); cases.setString(3, bridgeBankId);
                    cases.setDouble(4, number(preview.get("totalDeposits")));
                    cases.setDouble(5, number(preview.get("estateValue")));
                    cases.setDouble(6, recoveryRatio); cases.setDouble(7, subsidy);
                    cases.setString(8, administrator.toString()); cases.setLong(9, now()); cases.executeUpdate();
                }
                connection.commit();
                return success("失败银行已由桥接银行接管并完成存款赔付", Map.of("caseId", caseId,
                        "bankId", bankId, "bridgeBankId", bridgeBankId,
                        "depositors", depositors.size(), "insuranceSubsidy", subsidy,
                        "recoveryRatio", recoveryRatio));
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                return fail("处置失败，全部账务已回滚: " + failure.getMessage());
            } finally { connection.setAutoCommit(true); }
        } catch (SQLException failure) { return fail("处置失败"); }
    }

    public void collectPremiums() {
        long current = now();
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return;
            connection.setAutoCommit(false);
            try {
                Fund fund = loadFund(connection);
                List<String> banks = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT b.id FROM ks_bank_banks b
                        LEFT JOIN ks_bank_risk_state r ON r.bank_id=b.id
                        WHERE b.type='COMMERCIAL' AND b.status='ACTIVE'
                          AND COALESCE(r.operating_status,'NORMAL') IN ('NORMAL','WATCH')
                        """)) {
                    try (ResultSet rows = statement.executeQuery()) { while (rows.next()) banks.add(rows.getString(1)); }
                }
                for (String bankId : banks) {
                    long last = 0;
                    try (PreparedStatement membership = connection.prepareStatement(
                            "SELECT last_premium_at FROM ks_bank_insurance_membership WHERE bank_id=?")) {
                        membership.setString(1, bankId);
                        try (ResultSet row = membership.executeQuery()) { if (row.next()) last = row.getLong(1); }
                    }
                    if (last > 0 && current - last < PREMIUM_PERIOD) continue;
                    double deposits = deposits(connection, bankId);
                    double premium = round(deposits * fund.premiumRate());
                    if (premium <= 0) { upsertMembership(connection, bankId, 0, current); continue; }
                    try (PreparedStatement debit = connection.prepareStatement(
                            "UPDATE ks_bank_banks SET total_assets=total_assets-? WHERE id=? AND status='ACTIVE' AND total_assets>=?")) {
                        debit.setDouble(1, premium); debit.setString(2, bankId); debit.setDouble(3, premium);
                        if (debit.executeUpdate() != 1) continue;
                    }
                    try (PreparedStatement credit = connection.prepareStatement(
                            "UPDATE ks_bank_insurance_fund SET balance=balance+?,updated_at=? WHERE id=?")) {
                        credit.setDouble(1, premium); credit.setLong(2, current); credit.setString(3, FUND_ID); credit.executeUpdate();
                    }
                    upsertMembership(connection, bankId, premium, current);
                }
                connection.commit();
            } catch (SQLException failure) { connection.rollback(); throw failure; }
            finally { connection.setAutoCommit(true); }
        } catch (SQLException failure) {
            eco.getLogger().warning("[存款保险] 保费结算失败: " + failure.getMessage());
        }
    }

    private Map<String, Object> resolutionPreview(Connection connection, String bankId,
                                                  String bridgeBankId, boolean requireBridge) throws SQLException {
        if (bankId == null || bankId.isBlank() || bridgeBankId == null || bridgeBankId.isBlank()
                || bankId.equals(bridgeBankId)) return fail("接管参数无效");
        String operating;
        double liquidity;
        try (PreparedStatement bank = connection.prepareStatement("""
                SELECT b.total_assets,b.status,COALESCE(r.operating_status,'NORMAL')
                FROM ks_bank_banks b LEFT JOIN ks_bank_risk_state r ON r.bank_id=b.id WHERE b.id=?
                """)) {
            bank.setString(1, bankId);
            try (ResultSet row = bank.executeQuery()) {
                if (!row.next() || !"ACTIVE".equals(row.getString(2))) return fail("待处置银行不存在或已清算");
                liquidity = Math.max(0, row.getDouble(1)); operating = row.getString(3);
            }
        }
        if (requireBridge) {
            try (PreparedStatement bridge = connection.prepareStatement(
                    "SELECT COALESCE(r.operating_status,'NORMAL') FROM ks_bank_banks b "
                            + "LEFT JOIN ks_bank_risk_state r ON r.bank_id=b.id "
                            + "WHERE b.id=? AND b.status='ACTIVE' AND b.type<>'CENTRAL'")) {
                bridge.setString(1, bridgeBankId);
                try (ResultSet row = bridge.executeQuery()) {
                    if (!row.next() || List.of("RESTRICTED", "RESOLUTION", "RESOLVED").contains(row.getString(1))) {
                        return fail("桥接银行不存在、风险状态不合格或不可用");
                    }
                }
            }
        }
        double unsettledPayouts = scalar(connection,
                "SELECT COUNT(*) FROM ks_bank_loans WHERE bank_id=? "
                        + "AND status IN ('PENDING_PAYOUT','PAYOUT_SETTLING','RECONCILE_REQUIRED')", bankId);
        double unsettledRepayments = scalar(connection,
                "SELECT COUNT(*) FROM ks_bank_loan_repayment_settlements WHERE bank_id=? "
                        + "AND status NOT IN ('FINALIZED','COMPENSATED')", bankId);
        if (unsettledPayouts > 0 || unsettledRepayments > 0) {
            return fail("银行仍有结果未确认的放款或还款，请先在人工复核中心完成对账");
        }
        List<Depositor> depositors = loadDepositors(connection, bankId);
        double totalDeposits = depositors.stream().mapToDouble(Depositor::amount).sum();
        double performing = scalar(connection,
                "SELECT COALESCE(SUM(remaining),0) FROM ks_bank_loans WHERE bank_id=? AND status='ACTIVE'", bankId)
                + scalar(connection,
                "SELECT COALESCE(SUM(remaining),0) FROM ks_bank_enterprise_loans WHERE bank_id=? AND status='ACTIVE'", bankId);
        double overdue = scalar(connection,
                "SELECT COALESCE(SUM(remaining),0) FROM ks_bank_loans WHERE bank_id=? AND status='OVERDUE'", bankId)
                + scalar(connection,
                "SELECT COALESCE(SUM(remaining),0) FROM ks_bank_enterprise_loans WHERE bank_id=? AND status='OVERDUE'", bankId);
        double estate = round(liquidity + performing * 0.80 + overdue * 0.20);
        double recovery = totalDeposits <= 0 ? 1 : Math.max(0, Math.min(1, estate / totalDeposits));
        Fund fund = loadFund(connection);
        double insuredTotal = 0;
        double uninsuredTotal = 0;
        for (Depositor depositor : depositors) {
            insuredTotal += Math.min(depositor.amount(), fund.coverageLimit());
            uninsuredTotal += Math.max(0, depositor.amount() - fund.coverageLimit());
        }
        double subsidy = round(insuredTotal * (1 - recovery));
        double haircut = round(uninsuredTotal * (1 - recovery));
        return success("处置测算完成", Map.ofEntries(
                Map.entry("bankId", bankId), Map.entry("bridgeBankId", bridgeBankId),
                Map.entry("operatingStatus", operating), Map.entry("liquidity", liquidity),
                Map.entry("totalDeposits", round(totalDeposits)), Map.entry("depositorCount", depositors.size()),
                Map.entry("performingLoans", round(performing)), Map.entry("overdueLoans", round(overdue)),
                Map.entry("estateValue", estate), Map.entry("recoveryRatio", recovery),
                Map.entry("coverageLimit", fund.coverageLimit()), Map.entry("insuredDeposits", round(insuredTotal)),
                Map.entry("uninsuredDeposits", round(uninsuredTotal)), Map.entry("uninsuredHaircut", haircut),
                Map.entry("insuranceSubsidy", subsidy), Map.entry("fundBalance", fund.balance()),
                Map.entry("fundSufficient", fund.balance() + 0.000_001 >= subsidy)));
    }

    private void transferEstate(Connection connection, String failed, String bridge,
                                double subsidy, double liquidity) throws SQLException {
        try (PreparedStatement bridgeAssets = connection.prepareStatement(
                "UPDATE ks_bank_banks SET total_assets=total_assets+? WHERE id=? AND status='ACTIVE'")) {
            bridgeAssets.setDouble(1, liquidity + subsidy); bridgeAssets.setString(2, bridge);
            if (bridgeAssets.executeUpdate() != 1) throw new SQLException("bridge bank changed");
        }
        for (String table : List.of("ks_bank_loans", "ks_bank_enterprise_loans", "ks_bank_collateral",
                "ks_bank_player_collateral", "ks_bank_collateral_auctions")) {
            try (PreparedStatement transfer = connection.prepareStatement(
                    "UPDATE " + table + " SET bank_id=? WHERE bank_id=?")) {
                transfer.setString(1, bridge); transfer.setString(2, failed); transfer.executeUpdate();
            } catch (SQLException optionalTableMissing) {
                if (!("ks_bank_collateral".equals(table) || "ks_bank_player_collateral".equals(table))) throw optionalTableMissing;
            }
        }
        try (PreparedStatement plots = connection.prepareStatement(
                "UPDATE ks_re_plots SET owner_id=? WHERE owner_type='BANK' AND owner_id=?")) {
            plots.setString(1, bridge); plots.setString(2, failed); plots.executeUpdate();
        }
        try (PreparedStatement houses = connection.prepareStatement(
                "UPDATE ks_re_houses SET owner_id=? WHERE owner_type='BANK' AND owner_id=?")) {
            houses.setString(1, bridge); houses.setString(2, failed); houses.executeUpdate();
        }
        try (PreparedStatement offers = connection.prepareStatement(
                "UPDATE ks_bank_share_offerings SET status='CANCELLED',version=version+1 WHERE bank_id=? AND status IN ('OPEN','SETTLING')")) {
            offers.setString(1, failed); offers.executeUpdate();
        }
        try (PreparedStatement shares = connection.prepareStatement(
                "UPDATE ks_bank_share_ledger SET reserved_shares=0,updated_at=? WHERE bank_id=?")) {
            shares.setLong(1, now()); shares.setString(2, failed); shares.executeUpdate();
        }
        try (PreparedStatement risk = connection.prepareStatement(
                "UPDATE ks_bank_risk_state SET operating_status='RESOLVED',risk_rating='E',last_assessed_at=? WHERE bank_id=?")) {
            risk.setLong(1, now()); risk.setString(2, failed); risk.executeUpdate();
        }
        try (PreparedStatement bank = connection.prepareStatement(
                "UPDATE ks_bank_banks SET total_assets=0,status='LIQUIDATED' WHERE id=? AND status='ACTIVE'")) {
            bank.setString(1, failed);
            if (bank.executeUpdate() != 1) throw new SQLException("failed bank changed");
        }
    }

    private void releasePendingApplications(Connection connection, String bankId) throws SQLException {
        List<String> requests = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM ks_bank_loan_requests WHERE bank_id=? AND status='PENDING'")) {
            statement.setString(1, bankId);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) requests.add(rows.getString(1)); }
        }
        for (String requestId : requests) PlayerLoanCollateralStore.releaseRequest(connection, requestId, now());
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ks_bank_loan_requests SET status='RESOLVED',decided_at=? WHERE bank_id=? AND status='PENDING'")) {
            statement.setLong(1, now()); statement.setString(2, bankId); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ks_bank_restructure_requests SET status='REJECTED',decided_at=? WHERE bank_id=? AND status='PENDING'")) {
            statement.setLong(1, now()); statement.setString(2, bankId); statement.executeUpdate();
        }
    }

    private List<Depositor> loadDepositors(Connection connection, String bankId) throws SQLException {
        Map<UUID, Double> totals = new LinkedHashMap<>();
        try (PreparedStatement accounts = connection.prepareStatement(
                "SELECT player_uuid,balance FROM ks_bank_accounts WHERE bank_id=? AND balance>0")) {
            accounts.setString(1, bankId);
            try (ResultSet rows = accounts.executeQuery()) {
                while (rows.next()) addDeposit(totals, rows.getString(1), rows.getDouble(2));
            }
        }
        try (PreparedStatement terms = connection.prepareStatement(
                "SELECT player_uuid,principal,fixed_rate,opened_at,matures_at,accrued_interest "
                        + "FROM ks_bank_term_deposits WHERE bank_id=? AND status='ACTIVE'")) {
            terms.setString(1, bankId);
            try (ResultSet rows = terms.executeQuery()) {
                while (rows.next()) {
                    double principal = rows.getDouble(2);
                    long opened = rows.getLong(4);
                    long matures = rows.getLong(5);
                    double elapsed = Math.max(0, Math.min(1,
                            (now() - opened) / (double) Math.max(1, matures - opened)));
                    double contractualInterest = principal * Math.max(0, rows.getDouble(3)) * elapsed;
                    addDeposit(totals, rows.getString(1), principal
                            + Math.max(rows.getDouble(6), contractualInterest));
                }
            }
        }
        List<Depositor> out = new ArrayList<>();
        for (Map.Entry<UUID, Double> entry : totals.entrySet()) out.add(new Depositor(entry.getKey(), round(entry.getValue())));
        return List.copyOf(out);
    }

    private void addDeposit(Map<UUID, Double> totals, String rawUuid, double amount) throws SQLException {
        try {
            UUID player = UUID.fromString(rawUuid);
            if (amount > 0) totals.merge(player, amount, Double::sum);
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("invalid depositor UUID: " + rawUuid, invalid);
        }
    }

    private Fund loadFund(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance,coverage_limit,premium_rate FROM ks_bank_insurance_fund WHERE id=?")) {
            statement.setString(1, FUND_ID);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("insurance fund missing");
                return new Fund(row.getDouble(1), row.getDouble(2), row.getDouble(3));
            }
        }
    }

    private void upsertMembership(Connection connection, String bankId, double premium, long current) throws SQLException {
        BankSqlMutation.upsert(connection,
                "UPDATE ks_bank_insurance_membership SET last_premium_at=?,total_premiums=total_premiums+?,status='ACTIVE' WHERE bank_id=?",
                update -> { update.setLong(1, current); update.setDouble(2, premium); update.setString(3, bankId); },
                "INSERT INTO ks_bank_insurance_membership(bank_id,last_premium_at,total_premiums,status) VALUES(?,?,?,'ACTIVE')",
                insert -> { insert.setString(1, bankId); insert.setLong(2, current); insert.setDouble(3, premium); });
    }

    private double deposits(Connection connection, String bankId) throws SQLException {
        return scalar(connection, "SELECT COALESCE(SUM(balance),0) FROM ks_bank_accounts WHERE bank_id=?", bankId)
                + scalar(connection, "SELECT COALESCE(SUM(principal),0) FROM ks_bank_term_deposits WHERE bank_id=? AND status='ACTIVE'", bankId);
    }

    private double scalar(Connection connection, String sql, String bankId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, bankId);
            try (ResultSet row = statement.executeQuery()) { return row.next() ? row.getDouble(1) : 0; }
        }
    }

    private static double number(Object value) { return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value)); }
    static ClaimOutcome calculateClaim(double deposit, double coverageLimit, double recoveryRatio) {
        if (!Double.isFinite(deposit) || deposit < 0 || !Double.isFinite(coverageLimit) || coverageLimit < 0
                || !Double.isFinite(recoveryRatio) || recoveryRatio < 0 || recoveryRatio > 1) {
            throw new IllegalArgumentException("invalid resolution claim inputs");
        }
        double insured = round(Math.min(deposit, coverageLimit));
        double uninsured = round(Math.max(0, deposit - insured));
        double uninsuredRecovery = round(uninsured * recoveryRatio);
        double payout = round(insured + uninsuredRecovery);
        return new ClaimOutcome(insured, uninsured, uninsuredRecovery, round(deposit - payout), payout);
    }
    private static double round(double value) { return Math.rint(value * 100.0) / 100.0; }
    private static long now() { return System.currentTimeMillis() / 1000; }
    private static String shortId() { return UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT); }
    private static Map<String, Object> success(String message, Map<String, Object> values) {
        Map<String, Object> out = new LinkedHashMap<>(values); out.put("success", true); out.put("message", message); return out;
    }
    private static Map<String, Object> fail(String error) { return Map.of("success", false, "error", error); }

    private record Fund(double balance, double coverageLimit, double premiumRate) {}
    private record Depositor(UUID playerUuid, double amount) {}
    record ClaimOutcome(double insured, double uninsured, double uninsuredRecovery,
                        double haircut, double payout) {}
}
