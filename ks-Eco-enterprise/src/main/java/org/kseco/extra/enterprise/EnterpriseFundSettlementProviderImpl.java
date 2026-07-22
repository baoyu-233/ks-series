package org.kseco.extra.enterprise;

import org.kseco.extra.EnterpriseFundSettlementProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Keeps enterprise cash and its hosting bank mirror in the caller's settlement transaction. */
public final class EnterpriseFundSettlementProviderImpl implements EnterpriseFundSettlementProvider {
    @Override
    public void creditPropertySale(Connection connection, String enterpriseId, double amount, long now)
            throws SQLException {
        if (connection == null || enterpriseId == null || enterpriseId.isBlank()
                || !Double.isFinite(amount) || amount <= 0.0) {
            throw new SQLException("invalid enterprise property payout");
        }

        String bankId;
        try (PreparedStatement account = connection.prepareStatement("""
                SELECT ca.bank_id
                FROM ks_ent_corporate_accounts ca
                JOIN ks_ent_enterprises e ON e.id=ca.enterprise_id
                WHERE ca.enterprise_id=? AND e.status='ACTIVE'
                """)) {
            account.setString(1, enterpriseId);
            try (ResultSet rows = account.executeQuery()) {
                if (!rows.next()) throw new SQLException("enterprise corporate account is unavailable");
                bankId = rows.getString(1);
            }
        }
        try (PreparedStatement credit = connection.prepareStatement(
                "UPDATE ks_ent_corporate_accounts SET balance=balance+?,updated_at=? WHERE enterprise_id=?")) {
            credit.setDouble(1, amount);
            credit.setLong(2, now);
            credit.setString(3, enterpriseId);
            if (credit.executeUpdate() != 1) throw new SQLException("enterprise corporate account changed");
        }
        try (PreparedStatement bank = connection.prepareStatement(
                "UPDATE ks_banks SET balance=balance+? WHERE id=?")) {
            bank.setDouble(1, amount);
            bank.setString(2, bankId);
            if (bank.executeUpdate() != 1) throw new SQLException("enterprise hosting bank is unavailable");
        }
    }
}
