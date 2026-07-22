package org.kseco.extra.bank;

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

/**
 * Durable personal-loan collateral lifecycle. The stable asset row is the lock: an asset can only
 * be RESERVED/LOCKED by one request, and is reused after RELEASED/SOLD with an optimistic version.
 */
final class PlayerLoanCollateralStore {
    static final String RESERVED = "RESERVED";
    static final String LOCKED = "LOCKED";
    static final String RELEASED = "RELEASED";
    static final String SEIZED = "SEIZED";
    static final String SOLD = "SOLD";
    private static final long DEFAULT_GRACE_SECONDS = 3L * 86400L;

    private PlayerLoanCollateralStore() {}

    static void initialize(Connection connection) throws SQLException {
        String number = BusinessSchemaDialect.floatingPointType(BusinessSchemaDialect.detect(connection));
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_player_collateral ("
                    + "id VARCHAR(128) PRIMARY KEY,asset_type VARCHAR(32) NOT NULL,asset_ref VARCHAR(512) NOT NULL,"
                    + "owner_uuid VARCHAR(36) NOT NULL,bank_id VARCHAR(128) NOT NULL,product_type VARCHAR(32) NOT NULL,"
                    + "request_id VARCHAR(128),loan_id VARCHAR(128),appraised_value " + number + " NOT NULL,"
                    + "loan_to_value " + number + " NOT NULL,status VARCHAR(32) NOT NULL,locked_at BIGINT NOT NULL,"
                    + "released_at BIGINT NOT NULL DEFAULT 0,version BIGINT NOT NULL DEFAULT 0,"
                    + "UNIQUE(asset_type,asset_ref))");
        }
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_player_collateral_request",
                "ks_bank_player_collateral", "request_id", "status");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_player_collateral_loan",
                "ks_bank_player_collateral", "loan_id", "status");
    }

    static List<Map<String, Object>> eligible(Connection connection, UUID owner, String productType)
            throws SQLException {
        String product = normalize(productType);
        List<Map<String, Object>> out = new ArrayList<>();
        if ("CONSUMER".equals(product) || "STANDARD".equals(product)) return out;
        if ("HOME".equals(product) || "BUSINESS".equals(product)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id,price FROM ks_re_plots
                    WHERE owner_type='PLAYER' AND owner_id=? AND status='PURCHASED' ORDER BY price DESC
                    """)) {
                statement.setString(1, owner.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) addEligibleIfUnlocked(connection, out, "PLOT", rows.getString(1),
                            rows.getDouble(2), ltv(product, "PLOT"));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT h.id,p.price FROM ks_re_houses h JOIN ks_re_plots p ON p.id=h.plot_id
                    WHERE h.owner_type='PLAYER' AND h.owner_id=? AND p.status='PURCHASED' ORDER BY p.price DESC
                    """)) {
                statement.setString(1, owner.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) addEligibleIfUnlocked(connection, out, "HOUSE", rows.getString(1),
                            rows.getDouble(2), ltv(product, "HOUSE"));
                }
            }
        }
        if ("PROJECT".equals(product)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT p.id,p.budget FROM ks_ent_projects p JOIN ks_ent_bids b ON b.project_id=p.id
                    WHERE b.bidder_type='PLAYER' AND b.bidder_uuid=? AND p.status='AWARDED' AND b.status='AWARDED'
                    ORDER BY p.deadline
                    """)) {
                statement.setString(1, owner.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) addEligibleIfUnlocked(connection, out, "PROJECT_CONTRACT",
                            rows.getString(1), rows.getDouble(2), ltv(product, "PROJECT_CONTRACT"));
                }
            }
        }
        return List.copyOf(out);
    }

    static Appraisal appraise(Connection connection, UUID owner, String productType,
                              String assetType, String assetRef) throws SQLException {
        String product = normalize(productType);
        String type = normalize(assetType);
        String ref = assetRef == null ? "" : assetRef.trim();
        if (ref.isEmpty() || !allowed(product, type) || activeLockExists(connection, type, ref)
                || activePropertyListingExists(connection, type, ref)) return null;
        double value = 0;
        if ("PLOT".equals(type)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT price FROM ks_re_plots WHERE id=? AND owner_type='PLAYER' AND owner_id=? AND status='PURCHASED'")) {
                statement.setString(1, ref);
                statement.setString(2, owner.toString());
                try (ResultSet row = statement.executeQuery()) { if (row.next()) value = row.getDouble(1); }
            }
        } else if ("HOUSE".equals(type)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT p.price FROM ks_re_houses h JOIN ks_re_plots p ON p.id=h.plot_id
                    WHERE h.id=? AND h.owner_type='PLAYER' AND h.owner_id=? AND p.status='PURCHASED'
                    """)) {
                statement.setString(1, ref);
                statement.setString(2, owner.toString());
                try (ResultSet row = statement.executeQuery()) { if (row.next()) value = row.getDouble(1); }
            }
        } else if ("PROJECT_CONTRACT".equals(type)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT p.budget FROM ks_ent_projects p JOIN ks_ent_bids b ON b.project_id=p.id
                    WHERE p.id=? AND b.bidder_type='PLAYER' AND b.bidder_uuid=?
                      AND p.status='AWARDED' AND b.status='AWARDED'
                    """)) {
                statement.setString(1, ref);
                statement.setString(2, owner.toString());
                try (ResultSet row = statement.executeQuery()) { if (row.next()) value = row.getDouble(1); }
            }
        }
        double ratio = ltv(product, type);
        return value > 0 && ratio > 0 ? new Appraisal(type, ref, value, ratio, value * ratio) : null;
    }

    static Appraisal reserve(Connection connection, String requestId, String bankId, UUID owner,
                             String productType, String assetType, String assetRef, double principal,
                             long now) throws SQLException {
        Appraisal appraisal = appraise(connection, owner, productType, assetType, assetRef);
        if (appraisal == null || principal > appraisal.maxLoan() + 0.000_001) return null;
        String id = "PC-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT);
        int changed;
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE ks_bank_player_collateral
                SET owner_uuid=?,bank_id=?,product_type=?,request_id=?,loan_id=NULL,
                    appraised_value=?,loan_to_value=?,status='RESERVED',locked_at=?,released_at=0,version=version+1
                WHERE asset_type=? AND asset_ref=? AND status IN ('RELEASED','SOLD')
                """)) {
            update.setString(1, owner.toString());
            update.setString(2, bankId);
            update.setString(3, normalize(productType));
            update.setString(4, requestId);
            update.setDouble(5, appraisal.value());
            update.setDouble(6, appraisal.loanToValue());
            update.setLong(7, now);
            update.setString(8, appraisal.assetType());
            update.setString(9, appraisal.assetRef());
            changed = update.executeUpdate();
        }
        if (changed == 0) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO ks_bank_player_collateral
                    (id,asset_type,asset_ref,owner_uuid,bank_id,product_type,request_id,loan_id,
                     appraised_value,loan_to_value,status,locked_at,released_at,version)
                    VALUES (?,?,?,?,?,?,?,NULL,?,?,'RESERVED',?,0,0)
                    """)) {
                insert.setString(1, id);
                insert.setString(2, appraisal.assetType());
                insert.setString(3, appraisal.assetRef());
                insert.setString(4, owner.toString());
                insert.setString(5, bankId);
                insert.setString(6, normalize(productType));
                insert.setString(7, requestId);
                insert.setDouble(8, appraisal.value());
                insert.setDouble(9, appraisal.loanToValue());
                insert.setLong(10, now);
                try { insert.executeUpdate(); }
                catch (SQLException conflict) { return null; }
            }
        }
        lockAsset(connection, appraisal.assetType(), appraisal.assetRef(), owner.toString());
        return appraisal;
    }

    static boolean attachToLoan(Connection connection, String requestId, String loanId, long now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE ks_bank_player_collateral SET loan_id=?,status='LOCKED',locked_at=?,version=version+1
                WHERE request_id=? AND status='RESERVED'
                """)) {
            statement.setString(1, loanId);
            statement.setLong(2, now);
            statement.setString(3, requestId);
            return statement.executeUpdate() == 1;
        }
    }

    static boolean requestHasReservedCollateral(Connection connection, String requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM ks_bank_player_collateral WHERE request_id=? AND status='RESERVED'")) {
            statement.setString(1, requestId);
            try (ResultSet row = statement.executeQuery()) { return row.next(); }
        }
    }

    static void releaseRequest(Connection connection, String requestId, long now) throws SQLException {
        release(connection, "request_id", requestId, "RESERVED", now);
    }

    static void releaseLoan(Connection connection, String loanId, long now) throws SQLException {
        release(connection, "loan_id", loanId, "LOCKED", now);
    }

    static boolean returnToRequest(Connection connection, String requestId, String loanId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE ks_bank_player_collateral SET loan_id=NULL,status='RESERVED',version=version+1
                WHERE request_id=? AND loan_id=? AND status='LOCKED'
                """)) {
            statement.setString(1, requestId);
            statement.setString(2, loanId);
            return statement.executeUpdate() == 1;
        }
    }

    static void maintainDefaults(Connection connection, long now) throws SQLException {
        List<DefaultedLoan> loans = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT l.id,l.bank_id,l.borrower_uuid FROM ks_bank_loans l
                JOIN ks_bank_player_collateral c ON c.loan_id=l.id AND c.status='LOCKED'
                WHERE l.status='OVERDUE' AND COALESCE(l.grace_until,l.due_at)<?
                """)) {
            statement.setLong(1, now - DEFAULT_GRACE_SECONDS);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) loans.add(new DefaultedLoan(rows.getString(1), rows.getString(2), rows.getString(3)));
            }
        }
        for (DefaultedLoan loan : loans) seize(connection, loan, now);
    }

    private static void seize(Connection connection, DefaultedLoan loan, long now) throws SQLException {
        Collateral collateral;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,asset_type,asset_ref,appraised_value FROM ks_bank_player_collateral WHERE loan_id=? AND status='LOCKED'")) {
            statement.setString(1, loan.loanId());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return;
                collateral = new Collateral(row.getString(1), row.getString(2), row.getString(3), row.getDouble(4));
            }
        }
        int loanChanged;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ks_bank_loans SET status='DEFAULTED' WHERE id=? AND status='OVERDUE'")) {
            statement.setString(1, loan.loanId());
            loanChanged = statement.executeUpdate();
        }
        if (loanChanged != 1) return;
        forecloseAsset(connection, collateral.assetType(), collateral.assetRef(), loan.borrowerUuid(), loan.bankId());
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ks_bank_player_collateral SET status='SEIZED',released_at=?,version=version+1 WHERE id=? AND status='LOCKED'")) {
            statement.setLong(1, now);
            statement.setString(2, collateral.id());
            if (statement.executeUpdate() != 1) throw new SQLException("collateral seizure changed concurrently");
        }
        try (PreparedStatement auction = connection.prepareStatement("""
                INSERT INTO ks_bank_collateral_auctions
                (id,collateral_id,bank_id,asset_type,asset_ref,starting_price,current_price,status,opens_at,closes_at)
                VALUES (?,?,?,?,?,?,?,'OPEN',?,?)
                """)) {
            double start = Math.max(1, collateral.value() * 0.60);
            auction.setString(1, "AU-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT));
            auction.setString(2, collateral.id());
            auction.setString(3, loan.bankId());
            auction.setString(4, collateral.assetType());
            auction.setString(5, collateral.assetRef());
            auction.setDouble(6, start);
            auction.setDouble(7, start);
            auction.setLong(8, now);
            auction.setLong(9, now + 7L * 86400L);
            auction.executeUpdate();
        }
    }

    private static void release(Connection connection, String column, String value,
                                String expectedStatus, long now) throws SQLException {
        if (!("request_id".equals(column) || "loan_id".equals(column))) throw new SQLException("invalid release key");
        List<Collateral> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,asset_type,asset_ref,appraised_value FROM ks_bank_player_collateral WHERE "
                        + column + "=? AND status=?")) {
            statement.setString(1, value);
            statement.setString(2, expectedStatus);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(new Collateral(result.getString(1), result.getString(2),
                        result.getString(3), result.getDouble(4)));
            }
        }
        for (Collateral row : rows) {
            unlockAsset(connection, row.assetType(), row.assetRef());
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ks_bank_player_collateral SET status='RELEASED',released_at=?,version=version+1 WHERE id=? AND status=?")) {
                statement.setLong(1, now);
                statement.setString(2, row.id());
                statement.setString(3, expectedStatus);
                if (statement.executeUpdate() != 1) throw new SQLException("collateral release changed concurrently");
            }
        }
    }

    private static void lockAsset(Connection connection, String type, String ref, String owner) throws SQLException {
        if ("PLOT".equals(type)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ks_re_plots SET status='MORTGAGED' WHERE id=? AND owner_type='PLAYER' AND owner_id=? AND status='PURCHASED'")) {
                statement.setString(1, ref); statement.setString(2, owner);
                if (statement.executeUpdate() != 1) throw new SQLException("plot collateral changed before lock");
            }
        } else if ("HOUSE".equals(type)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ks_re_plots SET status='MORTGAGED' WHERE id=(SELECT plot_id FROM ks_re_houses WHERE id=?
                    AND owner_type='PLAYER' AND owner_id=?) AND status='PURCHASED'
                    """)) {
                statement.setString(1, ref); statement.setString(2, owner);
                if (statement.executeUpdate() != 1) throw new SQLException("house parent plot changed before lock");
            }
        } else {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ks_ent_bids SET status='MORTGAGED' WHERE project_id=? AND bidder_type='PLAYER'
                    AND bidder_uuid=? AND status='AWARDED'
                    """)) {
                statement.setString(1, ref); statement.setString(2, owner);
                if (statement.executeUpdate() != 1) throw new SQLException("project contract changed before lock");
            }
        }
    }

    private static void unlockAsset(Connection connection, String type, String ref) throws SQLException {
        if ("PLOT".equals(type)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ks_re_plots SET status='PURCHASED' WHERE id=? AND status='MORTGAGED'")) {
                statement.setString(1, ref); statement.executeUpdate();
            }
        } else if ("HOUSE".equals(type)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ks_re_plots SET status='PURCHASED' WHERE id=(SELECT plot_id FROM ks_re_houses WHERE id=?)
                    AND status='MORTGAGED'
                    """)) {
                statement.setString(1, ref); statement.executeUpdate();
            }
        } else {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ks_ent_bids SET status='AWARDED' WHERE project_id=? AND status='MORTGAGED'")) {
                statement.setString(1, ref); statement.executeUpdate();
            }
        }
    }

    private static void forecloseAsset(Connection connection, String type, String ref,
                                       String owner, String bankId) throws SQLException {
        int changed;
        if ("PLOT".equals(type)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ks_re_plots SET owner_type='BANK',owner_id=?,status='FORECLOSED'
                    WHERE id=? AND owner_type='PLAYER' AND owner_id=? AND status='MORTGAGED'
                    """)) {
                statement.setString(1, bankId); statement.setString(2, ref); statement.setString(3, owner);
                changed = statement.executeUpdate();
            }
        } else if ("HOUSE".equals(type)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ks_re_houses SET owner_type='BANK',owner_id=? WHERE id=? AND owner_type='PLAYER' AND owner_id=?")) {
                statement.setString(1, bankId); statement.setString(2, ref); statement.setString(3, owner);
                changed = statement.executeUpdate();
            }
            if (changed == 1) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE ks_re_plots SET status='PURCHASED' WHERE id=(SELECT plot_id FROM ks_re_houses WHERE id=?) AND status='MORTGAGED'")) {
                    statement.setString(1, ref); statement.executeUpdate();
                }
            }
        } else {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ks_ent_bids SET status='FORECLOSED' WHERE project_id=? AND bidder_type='PLAYER'
                    AND bidder_uuid=? AND status='MORTGAGED'
                    """)) {
                statement.setString(1, ref); statement.setString(2, owner); changed = statement.executeUpdate();
            }
        }
        if (changed != 1) throw new SQLException("collateral ownership changed before foreclosure");
    }

    private static void addEligibleIfUnlocked(Connection connection, List<Map<String, Object>> out,
                                              String type, String ref, double value, double ltv) throws SQLException {
        if (value <= 0 || activeLockExists(connection, type, ref)) return;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("assetType", type);
        item.put("assetRef", ref);
        item.put("appraisedValue", value);
        item.put("loanToValue", ltv);
        item.put("maxLoan", value * ltv);
        out.add(item);
    }

    private static boolean activeLockExists(Connection connection, String type, String ref) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM ks_bank_player_collateral WHERE asset_type=? AND asset_ref=?
                AND status IN ('RESERVED','LOCKED','SEIZED')
                """)) {
            statement.setString(1, type); statement.setString(2, ref);
            if (statement.executeQuery().next()) return true;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM ks_bank_collateral WHERE asset_type=? AND asset_ref=?
                AND status IN ('LOCKED','SEIZED')
                """)) {
            statement.setString(1, type); statement.setString(2, ref);
            return statement.executeQuery().next();
        } catch (SQLException optionalEnterpriseTableMissing) {
            return false;
        }
    }

    private static boolean activePropertyListingExists(Connection connection, String type, String ref)
            throws SQLException {
        if (!("HOUSE".equals(type) || "PLOT".equals(type))
                || !tableExists(connection, "ks_eco_active_property_listings")) return false;
        String sql = "HOUSE".equals(type)
                ? "SELECT 1 FROM ks_eco_active_property_listings WHERE house_id=?"
                : "SELECT 1 FROM ks_eco_active_property_listings l JOIN ks_re_houses h ON h.id=l.house_id WHERE h.plot_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ref);
            return statement.executeQuery().next();
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

    private static boolean allowed(String product, String type) {
        return switch (product) {
            case "HOME" -> "HOUSE".equals(type) || "PLOT".equals(type);
            case "BUSINESS" -> "HOUSE".equals(type) || "PLOT".equals(type);
            case "PROJECT" -> "PROJECT_CONTRACT".equals(type);
            default -> false;
        };
    }

    private static double ltv(String product, String type) {
        if (!allowed(product, type)) return 0;
        return switch (product) {
            case "HOME" -> 0.75;
            case "BUSINESS" -> 0.60;
            case "PROJECT" -> 0.70;
            default -> 0;
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    record Appraisal(String assetType, String assetRef, double value, double loanToValue, double maxLoan) {}
    private record Collateral(String id, String assetType, String assetRef, double value) {}
    private record DefaultedLoan(String loanId, String bankId, String borrowerUuid) {}
}
