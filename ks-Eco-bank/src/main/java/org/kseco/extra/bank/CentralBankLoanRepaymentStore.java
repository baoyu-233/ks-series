package org.kseco.extra.bank;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

final class CentralBankLoanRepaymentStore {

    static final int OPEN = 0;
    static final int CLAIMED = 2;
    static final int REPAID = 1;

    private CentralBankLoanRepaymentStore() {
    }

    static Result apply(Connection connection, String loanId) throws SQLException {
        if (loanId == null || loanId.isBlank()) return new Result(Outcome.NOT_PAYABLE, 0);

        try (PreparedStatement claim = connection.prepareStatement(
                "UPDATE ks_bank_cb_loans SET repaid=? WHERE id=? AND repaid=?")) {
            claim.setInt(1, CLAIMED);
            claim.setString(2, loanId);
            claim.setInt(3, OPEN);
            if (claim.executeUpdate() != 1) return new Result(Outcome.NOT_PAYABLE, 0);
        }

        String bankId;
        double amount;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT bank_id,principal,interest_rate FROM ks_bank_cb_loans WHERE id=? AND repaid=?")) {
            select.setString(1, loanId);
            select.setInt(2, CLAIMED);
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) throw new SQLException("Claimed central-bank loan disappeared");
                bankId = result.getString("bank_id");
                double principal = result.getDouble("principal");
                double rate = result.getDouble("interest_rate");
                if (!Double.isFinite(principal) || principal <= 0
                        || !Double.isFinite(rate) || rate < -1) {
                    throw new SQLException("Invalid central-bank loan terms");
                }
                amount = principal + principal * rate;
            }
        }
        if (!Double.isFinite(amount) || amount < 0) {
            throw new SQLException("Invalid central-bank repayment amount");
        }

        if (amount > 0) {
            try (PreparedStatement debit = connection.prepareStatement(
                    "UPDATE ks_bank_banks SET total_assets=total_assets-? " +
                            "WHERE id=? AND total_assets>=?")) {
                debit.setDouble(1, amount);
                debit.setString(2, bankId);
                debit.setDouble(3, amount);
                if (debit.executeUpdate() != 1) return new Result(Outcome.INSUFFICIENT_ASSETS, amount);
            }
        }

        try (PreparedStatement finish = connection.prepareStatement(
                "UPDATE ks_bank_cb_loans SET repaid=? WHERE id=? AND repaid=?")) {
            finish.setInt(1, REPAID);
            finish.setString(2, loanId);
            finish.setInt(3, CLAIMED);
            if (finish.executeUpdate() != 1) throw new SQLException("Central-bank loan claim was lost");
        }
        return new Result(Outcome.REPAID, amount);
    }

    enum Outcome {
        REPAID,
        NOT_PAYABLE,
        INSUFFICIENT_ASSETS
    }

    record Result(Outcome outcome, double amount) {
        boolean paid() {
            return outcome == Outcome.REPAID;
        }
    }
}
