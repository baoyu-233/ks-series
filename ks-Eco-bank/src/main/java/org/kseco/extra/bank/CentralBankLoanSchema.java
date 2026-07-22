package org.kseco.extra.bank;

import org.kseco.database.BusinessSchemaDialect;
import org.kseco.database.DatabaseDialect;

import java.sql.Connection;
import java.sql.SQLException;

final class CentralBankLoanSchema {

    static final String CREATE_TABLE_SQL = createTableSql(DatabaseDialect.SQLITE);

    static void initialize(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(createTableSql(BusinessSchemaDialect.detect(connection)));
        }
    }

    static String createTableSql(DatabaseDialect dialect) {
        String number = BusinessSchemaDialect.floatingPointType(dialect);
        return """
            CREATE TABLE IF NOT EXISTS ks_bank_cb_loans (
                id VARCHAR(191) PRIMARY KEY,
                bank_id VARCHAR(128) NOT NULL,
                principal %s NOT NULL,
                interest_rate %s NOT NULL,
                term_days INTEGER NOT NULL,
                issued_at BIGINT NOT NULL,
                due_at BIGINT NOT NULL,
                repaid INTEGER NOT NULL DEFAULT 0
            )
            """.formatted(number, number);
    }

    private CentralBankLoanSchema() {
    }
}
