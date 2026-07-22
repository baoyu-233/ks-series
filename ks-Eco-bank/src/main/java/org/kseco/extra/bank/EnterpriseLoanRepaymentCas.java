package org.kseco.extra.bank;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

final class EnterpriseLoanRepaymentCas {

    private EnterpriseLoanRepaymentCas() {
    }

    static boolean update(Connection connection, String loanId, String enterpriseId,
                          double expectedRemaining, String expectedStatus,
                          double newRemaining, String newStatus) throws SQLException {
        if (!("ACTIVE".equals(expectedStatus) || "OVERDUE".equals(expectedStatus))) return false;
        if (!(expectedStatus.equals(newStatus) || "PAID".equals(newStatus))) return false;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ks_bank_enterprise_loans SET remaining=?,status=? " +
                        "WHERE id=? AND enterprise_id=? AND remaining=? AND status=? " +
                        "AND status IN ('ACTIVE','OVERDUE')")) {
            statement.setDouble(1, Math.max(0, newRemaining));
            statement.setString(2, newStatus);
            statement.setString(3, loanId);
            statement.setString(4, enterpriseId);
            statement.setDouble(5, expectedRemaining);
            statement.setString(6, expectedStatus);
            return statement.executeUpdate() == 1;
        }
    }
}
