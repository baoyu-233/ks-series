package org.kseco.extra;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Enterprise-owned cash settlement operations that must participate in a caller-owned SQL transaction.
 * Implementations must not commit, roll back, or close the supplied connection.
 */
public interface EnterpriseFundSettlementProvider {
    void creditPropertySale(Connection connection, String enterpriseId, double amount, long now)
            throws SQLException;
}
