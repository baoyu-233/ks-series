package org.kseco.extra.politic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Locale;

final class ProposalTaxRateStore {

    private ProposalTaxRateStore() {}

    static String normalizeKey(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toUpperCase(Locale.ROOT);
    }

    static void upsertGeneral(Connection conn, String category, double rate, long now)
            throws SQLException {
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE ks_tax_rates SET rate=?,updated_at=? WHERE category=?")) {
            update.setDouble(1, rate);
            update.setLong(2, now);
            update.setString(3, category);
            if (update.executeUpdate() > 0) return;
        }
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO ks_tax_rates (category,rate,updated_at) VALUES (?,?,?)")) {
            insert.setString(1, category);
            insert.setDouble(2, rate);
            insert.setLong(3, now);
            insert.executeUpdate();
        } catch (SQLException insertFailure) {
            try (PreparedStatement retry = conn.prepareStatement(
                    "UPDATE ks_tax_rates SET rate=?,updated_at=? WHERE category=?")) {
                retry.setDouble(1, rate);
                retry.setLong(2, now);
                retry.setString(3, category);
                if (retry.executeUpdate() == 0) throw insertFailure;
            }
        }
    }

    static void upsertIndustry(Connection conn, String category, String industry,
                               double rate, long now) throws SQLException {
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE ks_tax_industry_rates SET rate=?,updated_at=? "
                        + "WHERE category=? AND industry=?")) {
            update.setDouble(1, rate);
            update.setLong(2, now);
            update.setString(3, category);
            update.setString(4, industry);
            if (update.executeUpdate() > 0) return;
        }
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO ks_tax_industry_rates "
                        + "(category,industry,rate,updated_at) VALUES (?,?,?,?)")) {
            insert.setString(1, category);
            insert.setString(2, industry);
            insert.setDouble(3, rate);
            insert.setLong(4, now);
            insert.executeUpdate();
        } catch (SQLException insertFailure) {
            try (PreparedStatement retry = conn.prepareStatement(
                    "UPDATE ks_tax_industry_rates SET rate=?,updated_at=? "
                            + "WHERE category=? AND industry=?")) {
                retry.setDouble(1, rate);
                retry.setLong(2, now);
                retry.setString(3, category);
                retry.setString(4, industry);
                if (retry.executeUpdate() == 0) throw insertFailure;
            }
        }
    }
}
