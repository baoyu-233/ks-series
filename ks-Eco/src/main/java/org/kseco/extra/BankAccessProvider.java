package org.kseco.extra;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** Bank Extra-owned access API. ks-Eco only forwards web requests to this provider. */
public interface BankAccessProvider {
    record SettlementReview(String id, String loanId, UUID borrowerUuid, String bankId,
                            double amount, double expectedRemaining, String reviewStage,
                            String lastError) { }

    record SettlementResolution(String status, String reviewStage, String message) { }

    boolean handleAccessRequest(HttpExchange exchange, String path, String query) throws IOException;

    boolean hasPermission(String bankId, UUID playerUuid, String permission);

    List<SettlementReview> listLoanRepaymentReviews() throws SQLException;

    SettlementResolution resolveLoanRepaymentReview(String id, String action) throws SQLException;
}
