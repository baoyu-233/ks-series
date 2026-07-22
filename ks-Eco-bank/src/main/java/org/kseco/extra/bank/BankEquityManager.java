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

/** Internal-account share registry, primary capital raises and secondary acquisitions. */
public final class BankEquityManager {
    private static final long INITIAL_SHARES = 1_000_000L;
    private static final long AUTHORIZED_SHARES = 10_000_000L;
    private final KsEco eco;
    private final BankManager bankManager;

    public BankEquityManager(KsEco eco, BankManager bankManager) {
        this.eco = eco;
        this.bankManager = bankManager;
    }

    public void init() {
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return;
            initialize(connection);
            List<String> bankIds = new ArrayList<>();
            try (PreparedStatement banks = connection.prepareStatement(
                    "SELECT id FROM ks_bank_banks WHERE type='COMMERCIAL'")) {
                try (ResultSet rows = banks.executeQuery()) {
                    while (rows.next()) bankIds.add(rows.getString(1));
                }
            }
            for (String bankId : bankIds) ensureInitialized(connection, bankId);
        } catch (SQLException failure) {
            eco.getLogger().warning("[银行股权] 初始化失败: " + failure.getMessage());
        }
    }

    static void initialize(Connection connection) throws SQLException {
        String number = BusinessSchemaDialect.floatingPointType(BusinessSchemaDialect.detect(connection));
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_equity_state ("
                    + "bank_id VARCHAR(128) PRIMARY KEY,total_issued BIGINT NOT NULL,authorized_shares BIGINT NOT NULL,"
                    + "paid_in_capital " + number + " NOT NULL DEFAULT 0,updated_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_share_ledger ("
                    + "bank_id VARCHAR(128) NOT NULL,shareholder_uuid VARCHAR(36) NOT NULL,shares BIGINT NOT NULL,"
                    + "reserved_shares BIGINT NOT NULL DEFAULT 0,cost_basis " + number + " NOT NULL DEFAULT 0,"
                    + "updated_at BIGINT NOT NULL,PRIMARY KEY(bank_id,shareholder_uuid))");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_share_offerings ("
                    + "id VARCHAR(128) PRIMARY KEY,bank_id VARCHAR(128) NOT NULL,offer_type VARCHAR(32) NOT NULL,"
                    + "seller_uuid VARCHAR(36),shares BIGINT NOT NULL,price_per_share " + number + " NOT NULL,"
                    + "status VARCHAR(32) NOT NULL,created_by VARCHAR(36) NOT NULL,created_at BIGINT NOT NULL,"
                    + "accepted_by VARCHAR(36),accepted_at BIGINT,version BIGINT NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_share_transactions ("
                    + "id VARCHAR(128) PRIMARY KEY,offering_id VARCHAR(128) NOT NULL,bank_id VARCHAR(128) NOT NULL,"
                    + "seller_uuid VARCHAR(36),buyer_uuid VARCHAR(36) NOT NULL,shares BIGINT NOT NULL,"
                    + "price_per_share " + number + " NOT NULL,total_amount " + number + " NOT NULL,"
                    + "transaction_type VARCHAR(32) NOT NULL,executed_at BIGINT NOT NULL)");
        }
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_bank_share_offers",
                "ks_bank_share_offerings", "bank_id", "status", "created_at");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_bank_share_owner",
                "ks_bank_share_ledger", "shareholder_uuid", "bank_id");
    }

    public List<Map<String, Object>> portfolio(UUID playerUuid) {
        if (playerUuid == null) return List.of();
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return List.of();
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT l.*,b.name,s.total_issued FROM ks_bank_share_ledger l
                    JOIN ks_bank_banks b ON b.id=l.bank_id JOIN ks_bank_equity_state s ON s.bank_id=l.bank_id
                    WHERE l.shareholder_uuid=? AND l.shares>0 ORDER BY b.name
                    """)) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Map<String, Object> row = row(rows);
                        long issued = rows.getLong("total_issued");
                        row.put("ownershipPercent", issued <= 0 ? 0 : rows.getLong("shares") * 100.0 / issued);
                        out.add(row);
                    }
                }
            }
            return List.copyOf(out);
        } catch (SQLException failure) {
            return List.of();
        }
    }

    public Map<String, Object> capTable(String bankId) {
        if (bankId == null || bankId.isBlank()) return fail("银行参数无效");
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return fail("数据库不可用");
            ensureInitialized(connection, bankId);
            long issued;
            long authorized;
            double paidIn;
            try (PreparedStatement state = connection.prepareStatement(
                    "SELECT total_issued,authorized_shares,paid_in_capital FROM ks_bank_equity_state WHERE bank_id=?")) {
                state.setString(1, bankId);
                try (ResultSet row = state.executeQuery()) {
                    if (!row.next()) return fail("银行不存在或不是商业银行");
                    issued = row.getLong(1); authorized = row.getLong(2); paidIn = row.getDouble(3);
                }
            }
            List<Map<String, Object>> holders = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM ks_bank_share_ledger WHERE bank_id=? AND shares>0 ORDER BY shares DESC,shareholder_uuid")) {
                statement.setString(1, bankId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Map<String, Object> holder = row(rows);
                        holder.put("ownershipPercent", issued <= 0 ? 0 : rows.getLong("shares") * 100.0 / issued);
                        holder.put("controlling", issued > 0 && rows.getLong("shares") * 2 > issued);
                        holders.add(holder);
                    }
                }
            }
            return success("股权结构已加载", Map.of("bankId", bankId, "totalIssued", issued,
                    "authorizedShares", authorized, "availableForIssue", authorized - issued,
                    "paidInCapital", paidIn, "shareholders", holders));
        } catch (SQLException failure) {
            return fail("股权结构读取失败");
        }
    }

    public List<Map<String, Object>> offerings(String bankId) {
        if (bankId == null || bankId.isBlank()) return List.of();
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return List.of();
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM ks_bank_share_offerings WHERE bank_id=? AND status='OPEN' ORDER BY created_at DESC")) {
                statement.setString(1, bankId);
                try (ResultSet rows = statement.executeQuery()) { while (rows.next()) out.add(row(rows)); }
            }
            return List.copyOf(out);
        } catch (SQLException failure) { return List.of(); }
    }

    public Map<String, Object> createOffering(String bankId, UUID actor, String offerType,
                                              long shares, double pricePerShare) {
        String type = normalize(offerType);
        if (bankId == null || bankId.isBlank() || actor == null || shares <= 0
                || shares > AUTHORIZED_SHARES
                || !Double.isFinite(pricePerShare) || pricePerShare <= 0
                || !("PRIMARY".equals(type) || "SECONDARY".equals(type))) return fail("发行参数无效");
        double total = shares * pricePerShare;
        try {
            if (!Double.isFinite(total)) return fail("交易总价无效");
            BankInterestSettlementStore.toMinor(total);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return fail("交易总价超出可结算范围");
        }
        String id = "SO-" + shortId();
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return fail("数据库不可用");
            connection.setAutoCommit(false);
            try {
                ensureInitialized(connection, bankId);
                if (!bankOpenForEquity(connection, bankId)) {
                    connection.rollback();
                    return fail("银行当前处于限制、处置或清算状态，暂停股份交易");
                }
                if ("PRIMARY".equals(type)) {
                    if (!isCurrentOwner(connection, bankId, actor)) { connection.rollback(); return fail("只有银行股东可发起增资"); }
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT total_issued,authorized_shares FROM ks_bank_equity_state WHERE bank_id=?")) {
                        statement.setString(1, bankId);
                        try (ResultSet row = statement.executeQuery()) {
                            if (!row.next() || row.getLong(1) + shares > row.getLong(2)) {
                                connection.rollback(); return fail("超过授权股本额度");
                            }
                        }
                    }
                } else {
                    try (PreparedStatement reserve = connection.prepareStatement("""
                            UPDATE ks_bank_share_ledger SET reserved_shares=reserved_shares+?,updated_at=?
                            WHERE bank_id=? AND shareholder_uuid=? AND shares-reserved_shares>=?
                            """)) {
                        reserve.setLong(1, shares); reserve.setLong(2, now()); reserve.setString(3, bankId);
                        reserve.setString(4, actor.toString()); reserve.setLong(5, shares);
                        if (reserve.executeUpdate() != 1) { connection.rollback(); return fail("可出售股份不足"); }
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO ks_bank_share_offerings
                        (id,bank_id,offer_type,seller_uuid,shares,price_per_share,status,created_by,created_at,version)
                        VALUES (?,?,?,?,?,?,'OPEN',?,?,0)
                        """)) {
                    insert.setString(1, id); insert.setString(2, bankId); insert.setString(3, type);
                    if ("SECONDARY".equals(type)) insert.setString(4, actor.toString()); else insert.setNull(4, java.sql.Types.VARCHAR);
                    insert.setLong(5, shares); insert.setDouble(6, pricePerShare);
                    insert.setString(7, actor.toString()); insert.setLong(8, now()); insert.executeUpdate();
                }
                connection.commit();
                return success("股份发行已挂牌", Map.of("offeringId", id, "offerType", type,
                        "shares", shares, "pricePerShare", pricePerShare, "total", total));
            } catch (SQLException failure) {
                connection.rollback(); throw failure;
            } finally { connection.setAutoCommit(true); }
        } catch (SQLException failure) {
            return fail("股份发行失败");
        }
    }

    public Map<String, Object> acceptOffering(String offeringId, UUID buyer) {
        if (offeringId == null || offeringId.isBlank() || buyer == null) return fail("交易参数无效");
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return fail("数据库不可用");
            connection.setAutoCommit(false);
            try {
                Offering offer = findOffering(connection, offeringId);
                if (offer == null || !"OPEN".equals(offer.status())) { connection.rollback(); return fail("发行已结束"); }
                if (!bankOpenForEquity(connection, offer.bankId())) {
                    connection.rollback();
                    return fail("银行当前处于限制、处置或清算状态，暂停股份交易");
                }
                if (buyer.toString().equals(offer.sellerUuid())) { connection.rollback(); return fail("不能购买自己的挂牌股份"); }
                try (PreparedStatement claim = connection.prepareStatement(
                        "UPDATE ks_bank_share_offerings SET status='SETTLING',accepted_by=?,accepted_at=?,version=version+1 WHERE id=? AND status='OPEN' AND version=?")) {
                    claim.setString(1, buyer.toString()); claim.setLong(2, now()); claim.setString(3, offeringId);
                    claim.setLong(4, offer.version());
                    if (claim.executeUpdate() != 1) { connection.rollback(); return fail("发行已被其他玩家认购"); }
                }
                long totalMinor = BankInterestSettlementStore.toMinor(offer.shares() * offer.pricePerShare());
                BankInterestSettlementStore.SettlementResult debit = bankManager.mutateAccountBalance(connection,
                        offer.bankId(), offer.bankId() + ":" + buyer, buyer, now(), -totalMinor, false);
                if (!debit.applied()) { connection.rollback(); return fail("请先在目标银行存入足够活期资金"); }
                if ("PRIMARY".equals(offer.offerType())) {
                    try (PreparedStatement state = connection.prepareStatement("""
                            UPDATE ks_bank_equity_state SET total_issued=total_issued+?,paid_in_capital=paid_in_capital+?,updated_at=?
                            WHERE bank_id=? AND total_issued+?<=authorized_shares
                            """)) {
                        state.setLong(1, offer.shares()); state.setDouble(2, BankInterestSettlementStore.fromMinor(totalMinor));
                        state.setLong(3, now()); state.setString(4, offer.bankId()); state.setLong(5, offer.shares());
                        if (state.executeUpdate() != 1) throw new SQLException("authorized share limit changed");
                    }
                } else {
                    UUID seller = UUID.fromString(offer.sellerUuid());
                    BankInterestSettlementStore.SettlementResult credit = bankManager.mutateAccountBalance(connection,
                            offer.bankId(), offer.bankId() + ":" + seller, seller, now(), totalMinor, true);
                    if (!credit.applied()) throw new SQLException("seller account credit failed");
                    try (PreparedStatement sellerUpdate = connection.prepareStatement("""
                            UPDATE ks_bank_share_ledger SET shares=shares-?,reserved_shares=reserved_shares-?,updated_at=?
                            WHERE bank_id=? AND shareholder_uuid=? AND shares>=? AND reserved_shares>=?
                            """)) {
                        sellerUpdate.setLong(1, offer.shares()); sellerUpdate.setLong(2, offer.shares());
                        sellerUpdate.setLong(3, now()); sellerUpdate.setString(4, offer.bankId());
                        sellerUpdate.setString(5, offer.sellerUuid()); sellerUpdate.setLong(6, offer.shares());
                        sellerUpdate.setLong(7, offer.shares());
                        if (sellerUpdate.executeUpdate() != 1) throw new SQLException("seller shares changed");
                    }
                }
                addShares(connection, offer.bankId(), buyer, offer.shares(),
                        BankInterestSettlementStore.fromMinor(totalMinor));
                try (PreparedStatement finish = connection.prepareStatement(
                        "UPDATE ks_bank_share_offerings SET status='FILLED' WHERE id=? AND status='SETTLING'")) {
                    finish.setString(1, offeringId);
                    if (finish.executeUpdate() != 1) throw new SQLException("offering state changed");
                }
                try (PreparedStatement tx = connection.prepareStatement("""
                        INSERT INTO ks_bank_share_transactions
                        (id,offering_id,bank_id,seller_uuid,buyer_uuid,shares,price_per_share,total_amount,transaction_type,executed_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?)
                        """)) {
                    tx.setString(1, "ST-" + shortId()); tx.setString(2, offeringId); tx.setString(3, offer.bankId());
                    if (offer.sellerUuid() == null) tx.setNull(4, java.sql.Types.VARCHAR); else tx.setString(4, offer.sellerUuid());
                    tx.setString(5, buyer.toString()); tx.setLong(6, offer.shares()); tx.setDouble(7, offer.pricePerShare());
                    tx.setDouble(8, BankInterestSettlementStore.fromMinor(totalMinor)); tx.setString(9, offer.offerType());
                    tx.setLong(10, now()); tx.executeUpdate();
                }
                syncOwners(connection, offer.bankId());
                connection.commit();
                return success("股份认购完成", Map.of("bankId", offer.bankId(), "shares", offer.shares(),
                        "total", BankInterestSettlementStore.fromMinor(totalMinor)));
            } catch (SQLException | RuntimeException failure) {
                connection.rollback(); return fail("股份交易失败，账务已回滚");
            } finally { connection.setAutoCommit(true); }
        } catch (SQLException failure) { return fail("股份交易失败"); }
    }

    public Map<String, Object> cancelOffering(String offeringId, UUID actor) {
        if (offeringId == null || offeringId.isBlank() || actor == null) return fail("撤单参数无效");
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return fail("数据库不可用");
            connection.setAutoCommit(false);
            try {
                Offering offer = findOffering(connection, offeringId);
                if (offer == null || !"OPEN".equals(offer.status())) { connection.rollback(); return fail("挂牌已结束"); }
                String creator;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT created_by FROM ks_bank_share_offerings WHERE id=?")) {
                    statement.setString(1, offeringId);
                    try (ResultSet row = statement.executeQuery()) { creator = row.next() ? row.getString(1) : ""; }
                }
                if (!actor.toString().equals(creator)) { connection.rollback(); return fail("只能撤销本人发布的挂牌"); }
                try (PreparedStatement cancel = connection.prepareStatement(
                        "UPDATE ks_bank_share_offerings SET status='CANCELLED',version=version+1 WHERE id=? AND status='OPEN' AND version=?")) {
                    cancel.setString(1, offeringId); cancel.setLong(2, offer.version());
                    if (cancel.executeUpdate() != 1) { connection.rollback(); return fail("挂牌状态已变化"); }
                }
                if ("SECONDARY".equals(offer.offerType())) {
                    try (PreparedStatement release = connection.prepareStatement("""
                            UPDATE ks_bank_share_ledger SET reserved_shares=reserved_shares-?,updated_at=?
                            WHERE bank_id=? AND shareholder_uuid=? AND reserved_shares>=?
                            """)) {
                        release.setLong(1, offer.shares()); release.setLong(2, now()); release.setString(3, offer.bankId());
                        release.setString(4, offer.sellerUuid()); release.setLong(5, offer.shares());
                        if (release.executeUpdate() != 1) throw new SQLException("share reservation changed");
                    }
                }
                connection.commit();
                return success("股份挂牌已撤销", Map.of("offeringId", offeringId));
            } catch (SQLException failure) {
                connection.rollback(); return fail("撤单失败，状态已回滚");
            } finally { connection.setAutoCommit(true); }
        } catch (SQLException failure) { return fail("撤单失败"); }
    }

    static List<Shareholder> shareholders(Connection connection, String bankId) throws SQLException {
        List<Shareholder> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT shareholder_uuid,shares FROM ks_bank_share_ledger WHERE bank_id=? AND shares>0 ORDER BY shares DESC,shareholder_uuid")) {
            statement.setString(1, bankId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    try { out.add(new Shareholder(UUID.fromString(rows.getString(1)), rows.getLong(2))); }
                    catch (IllegalArgumentException ignored) {}
                }
            }
        }
        return List.copyOf(out);
    }

    static void ensureInitialized(Connection connection, String bankId) throws SQLException {
        boolean ownTransaction = connection.getAutoCommit();
        if (ownTransaction) connection.setAutoCommit(false);
        try {
            ensureInitializedInTransaction(connection, bankId);
            if (ownTransaction) connection.commit();
        } catch (SQLException | RuntimeException failure) {
            if (ownTransaction) connection.rollback();
            throw failure;
        } finally {
            if (ownTransaction) connection.setAutoCommit(true);
        }
    }

    private static void ensureInitializedInTransaction(Connection connection, String bankId) throws SQLException {
        try (PreparedStatement exists = connection.prepareStatement("SELECT 1 FROM ks_bank_equity_state WHERE bank_id=?")) {
            exists.setString(1, bankId);
            if (exists.executeQuery().next()) return;
        }
        String owners;
        double capital;
        try (PreparedStatement bank = connection.prepareStatement(
                "SELECT owner_uuids,total_assets FROM ks_bank_banks WHERE id=? AND type='COMMERCIAL'")) {
            bank.setString(1, bankId);
            try (ResultSet row = bank.executeQuery()) {
                if (!row.next()) return;
                owners = row.getString(1); capital = Math.max(0, row.getDouble(2));
            }
        }
        List<UUID> valid = new ArrayList<>();
        for (String raw : owners.split(",")) {
            try { UUID id = UUID.fromString(raw.trim()); if (!valid.contains(id)) valid.add(id); }
            catch (IllegalArgumentException ignored) {}
        }
        if (valid.isEmpty()) return;
        double deposits = optionalScalar(connection, "ks_bank_accounts",
                "SELECT COALESCE(SUM(balance),0) FROM ks_bank_accounts WHERE bank_id=?", bankId)
                + optionalScalar(connection, "ks_bank_term_deposits",
                "SELECT COALESCE(SUM(principal),0) FROM ks_bank_term_deposits WHERE bank_id=? AND status='ACTIVE'", bankId);
        double loans = optionalScalar(connection, "ks_bank_loans",
                "SELECT COALESCE(SUM(remaining),0) FROM ks_bank_loans WHERE bank_id=? AND status IN ('ACTIVE','OVERDUE')", bankId)
                + optionalScalar(connection, "ks_bank_enterprise_loans",
                "SELECT COALESCE(SUM(remaining),0) FROM ks_bank_enterprise_loans WHERE bank_id=? AND status IN ('ACTIVE','OVERDUE')", bankId);
        capital = Math.max(0, capital + loans - deposits);
        try (PreparedStatement state = connection.prepareStatement(
                "INSERT INTO ks_bank_equity_state(bank_id,total_issued,authorized_shares,paid_in_capital,updated_at) VALUES(?,?,?,?,?)")) {
            state.setString(1, bankId); state.setLong(2, INITIAL_SHARES); state.setLong(3, AUTHORIZED_SHARES);
            state.setDouble(4, capital); state.setLong(5, now()); state.executeUpdate();
        }
        long base = INITIAL_SHARES / valid.size();
        long remainder = INITIAL_SHARES % valid.size();
        for (int i = 0; i < valid.size(); i++) addShares(connection, bankId, valid.get(i),
                base + (i < remainder ? 1 : 0), capital / valid.size());
    }

    private static void addShares(Connection connection, String bankId, UUID owner, long shares, double cost) throws SQLException {
        int changed;
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE ks_bank_share_ledger SET shares=shares+?,cost_basis=cost_basis+?,updated_at=?
                WHERE bank_id=? AND shareholder_uuid=?
                """)) {
            update.setLong(1, shares); update.setDouble(2, cost); update.setLong(3, now());
            update.setString(4, bankId); update.setString(5, owner.toString()); changed = update.executeUpdate();
        }
        if (changed == 0) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO ks_bank_share_ledger(bank_id,shareholder_uuid,shares,reserved_shares,cost_basis,updated_at)
                    VALUES(?,?,?,0,?,?)
                    """)) {
                insert.setString(1, bankId); insert.setString(2, owner.toString()); insert.setLong(3, shares);
                insert.setDouble(4, cost); insert.setLong(5, now()); insert.executeUpdate();
            }
        }
    }

    private void syncOwners(Connection connection, String bankId) throws SQLException {
        long issued;
        try (PreparedStatement state = connection.prepareStatement("SELECT total_issued FROM ks_bank_equity_state WHERE bank_id=?")) {
            state.setString(1, bankId); try (ResultSet row = state.executeQuery()) { if (!row.next()) return; issued = row.getLong(1); }
        }
        List<String> owners = new ArrayList<>();
        String top = null;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT shareholder_uuid,shares FROM ks_bank_share_ledger WHERE bank_id=? AND shares>0 ORDER BY shares DESC,shareholder_uuid")) {
            statement.setString(1, bankId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (top == null) top = rows.getString(1);
                    if (rows.getLong(2) * 10 >= issued && owners.size() < 8) owners.add(rows.getString(1));
                }
            }
        }
        if (owners.isEmpty() && top != null) owners.add(top);
        try (PreparedStatement bank = connection.prepareStatement("UPDATE ks_bank_banks SET owner_uuids=? WHERE id=?")) {
            bank.setString(1, String.join(",", owners)); bank.setString(2, bankId); bank.executeUpdate();
        }
    }

    private boolean isCurrentOwner(Connection connection, String bankId, UUID actor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT owner_uuids FROM ks_bank_banks WHERE id=?")) {
            statement.setString(1, bankId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return false;
                for (String raw : row.getString(1).split(",")) if (actor.toString().equals(raw.trim())) return true;
                return false;
            }
        }
    }

    private static boolean bankOpenForEquity(Connection connection, String bankId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(r.operating_status,'NORMAL') FROM ks_bank_banks b
                LEFT JOIN ks_bank_risk_state r ON r.bank_id=b.id
                WHERE b.id=? AND b.status='ACTIVE' AND b.type='COMMERCIAL'
                """)) {
            statement.setString(1, bankId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() && !List.of("RESTRICTED", "RESOLUTION", "RESOLVED")
                        .contains(row.getString(1));
            }
        }
    }

    private Offering findOffering(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM ks_bank_share_offerings WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? new Offering(row.getString("id"), row.getString("bank_id"),
                        row.getString("offer_type"), row.getString("seller_uuid"), row.getLong("shares"),
                        row.getDouble("price_per_share"), row.getString("status"), row.getLong("version")) : null;
            }
        }
    }

    private static double optionalScalar(Connection connection, String table, String sql, String bankId) throws SQLException {
        if (!tableExists(connection, table)) return 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, bankId);
            try (ResultSet row = statement.executeQuery()) { return row.next() ? row.getDouble(1) : 0; }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        for (String candidate : List.of(table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT))) {
            try (ResultSet rows = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(),
                    candidate, new String[]{"TABLE"})) {
                if (rows.next()) return true;
            }
        }
        return false;
    }

    private static Map<String, Object> row(ResultSet row) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        var meta = row.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) out.put(meta.getColumnLabel(i), row.getObject(i));
        return out;
    }

    private static Map<String, Object> success(String message, Map<String, Object> values) {
        Map<String, Object> out = new LinkedHashMap<>(values); out.put("success", true); out.put("message", message); return out;
    }
    private static Map<String, Object> fail(String error) { return Map.of("success", false, "error", error); }
    private static long now() { return System.currentTimeMillis() / 1000; }
    private static String shortId() { return UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT); }
    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }

    record Shareholder(UUID playerUuid, long shares) {}
    private record Offering(String id, String bankId, String offerType, String sellerUuid,
                            long shares, double pricePerShare, String status, long version) {}
}
