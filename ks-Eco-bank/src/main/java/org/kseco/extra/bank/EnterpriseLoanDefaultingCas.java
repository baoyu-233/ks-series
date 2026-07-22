package org.kseco.extra.bank;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

final class EnterpriseLoanDefaultingCas {

    static final String OVERDUE = "OVERDUE";
    static final String DEFAULTING = "DEFAULTING";
    static final String DEFAULTED = "DEFAULTED";

    private EnterpriseLoanDefaultingCas() {
    }

    static boolean claim(Connection connection, String loanId, String enterpriseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ks_bank_enterprise_loans SET status=? " +
                        "WHERE id=? AND enterprise_id=? AND status=?")) {
            statement.setString(1, DEFAULTING);
            statement.setString(2, loanId);
            statement.setString(3, enterpriseId);
            statement.setString(4, OVERDUE);
            return statement.executeUpdate() == 1;
        }
    }

    static boolean finish(Connection connection, String loanId, String enterpriseId, long defaultAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ks_bank_enterprise_loans SET status=?,default_at=? " +
                        "WHERE id=? AND enterprise_id=? AND status=?")) {
            statement.setString(1, DEFAULTED);
            statement.setLong(2, defaultAt);
            statement.setString(3, loanId);
            statement.setString(4, enterpriseId);
            statement.setString(5, DEFAULTING);
            return statement.executeUpdate() == 1;
        }
    }
}
