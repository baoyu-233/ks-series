package org.kseco.extra.bank;

import org.kseco.database.BusinessSchemaDialect;
import org.kseco.database.DatabaseDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Portable schema boundary for the core bank/account/loan tables. */
final class BankSchema {
    private BankSchema() {
    }

    static void initialize(Connection connection) throws SQLException {
        initialize(connection, BusinessSchemaDialect.detect(connection));
    }

    static void initialize(Connection connection, DatabaseDialect dialect) throws SQLException {
        String number = BusinessSchemaDialect.floatingPointType(dialect);
        String generatedId = generatedId(dialect);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_banks ("
                    + "id VARCHAR(128) PRIMARY KEY,name VARCHAR(128) NOT NULL,type VARCHAR(32) NOT NULL DEFAULT 'COMMERCIAL',"
                    + "owner_uuids VARCHAR(1024) NOT NULL,total_assets " + number + " DEFAULT 0,"
                    + "reserve_ratio " + number + " DEFAULT 0.1,interest_rate " + number + " DEFAULT 0.03,"
                    + "loan_rate " + number + " DEFAULT 0.08,status VARCHAR(32) DEFAULT 'ACTIVE',created_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_accounts ("
                    + "id VARCHAR(191) PRIMARY KEY,bank_id VARCHAR(128) NOT NULL,player_uuid VARCHAR(36) NOT NULL,"
                    + "balance " + number + " DEFAULT 0,interest_earned " + number + " DEFAULT 0,opened_at BIGINT NOT NULL,"
                    + "FOREIGN KEY (bank_id) REFERENCES ks_bank_banks(id))");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_loans ("
                    + "id VARCHAR(128) PRIMARY KEY,bank_id VARCHAR(128) NOT NULL,borrower_uuid VARCHAR(36) NOT NULL,"
                    + "principal " + number + " NOT NULL,remaining " + number + " NOT NULL,interest_rate " + number + " NOT NULL,"
                    + "term_days INTEGER NOT NULL,issued_at BIGINT NOT NULL,due_at BIGINT NOT NULL,ever_overdue INTEGER DEFAULT 0,"
                    + "paid_at BIGINT,repayment_settlement_id VARCHAR(128),status VARCHAR(32) DEFAULT 'ACTIVE',"
                    + "FOREIGN KEY (bank_id) REFERENCES ks_bank_banks(id))");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_cb_rates (id " + generatedId
                    + ",base_rate " + number + " NOT NULL,reserve_requirement " + number + " NOT NULL,set_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_cb_config (config_key VARCHAR(128) PRIMARY KEY,config_value VARCHAR(2048) NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_rates (bank_id VARCHAR(128) PRIMARY KEY,loan_rate " + number
                    + " DEFAULT 0.05,deposit_rate " + number + " DEFAULT 0.01,updated_at BIGINT NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_members (bank_id VARCHAR(128),player_uuid VARCHAR(36),"
                    + "player_name VARCHAR(64),role VARCHAR(32) DEFAULT 'MEMBER',joined_at BIGINT NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY(bank_id,player_uuid))");
            ensureAccessTables(connection, statement);
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_money_supply (id " + generatedId + ",m0 " + number
                    + " NOT NULL,m1 " + number + " NOT NULL,m2 " + number + " NOT NULL,snapshot_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_loan_requests ("
                    + "id VARCHAR(128) PRIMARY KEY,bank_id VARCHAR(128) NOT NULL,borrower_uuid VARCHAR(36) NOT NULL,"
                    + "borrower_name VARCHAR(64),principal " + number + " NOT NULL,term_days INTEGER NOT NULL,"
                    + "quoted_rate " + number + ",quoted_total_due " + number + ",quote_base_rate " + number
                    + ",quote_risk_spread " + number + ",quote_term_spread " + number + ",quote_version INTEGER DEFAULT 1,"
                    + "credit_score INTEGER,credit_tier VARCHAR(16),status VARCHAR(32) DEFAULT 'PENDING',"
                    + "requested_at BIGINT NOT NULL,decided_at BIGINT,loan_id VARCHAR(128))");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_guidance_config (config_key VARCHAR(128) PRIMARY KEY,config_value VARCHAR(2048) NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_guidance_claims (player_uuid VARCHAR(36) PRIMARY KEY,"
                    + "loan_id VARCHAR(128) NOT NULL,claimed_at BIGINT NOT NULL)");
        }

        migrateLegacyConfigColumns(connection, dialect, "ks_bank_cb_config");
        migrateLegacyConfigColumns(connection, dialect, "ks_bank_guidance_config");
        BankInterestSettlementStore.createTables(connection);
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_accounts", "last_interest_at", "BIGINT");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_loan_requests", "quoted_rate", number);
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_loan_requests", "quoted_total_due", number);
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_loan_requests", "quote_base_rate", number);
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_loan_requests", "quote_risk_spread", number);
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_loan_requests", "quote_term_spread", number);
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_loan_requests", "quote_version", "INTEGER DEFAULT 1");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_loan_requests", "credit_score", "INTEGER");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_loan_requests", "credit_tier", "VARCHAR(16)");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_loans", "ever_overdue", "INTEGER DEFAULT 0");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_loans", "paid_at", "BIGINT");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_loans", "repayment_settlement_id", "VARCHAR(128)");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_bank_loans_borrower_status",
                "ks_bank_loans", "borrower_uuid", "status");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_bank_requests_borrower_status",
                "ks_bank_loan_requests", "bank_id", "borrower_uuid", "status");
        migrateAccountIds(connection);
        LoanRepaymentSettlementStore.initialize(connection, dialect);
    }

    static void initializeGameplay(Connection connection) throws SQLException {
        DatabaseDialect dialect = BusinessSchemaDialect.detect(connection);
        String number = BusinessSchemaDialect.floatingPointType(dialect);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_deposit_products ("
                    + "bank_id VARCHAR(128) NOT NULL,product_code VARCHAR(32) NOT NULL,name VARCHAR(128) NOT NULL,"
                    + "term_days INTEGER NOT NULL,fixed_rate " + number + " NOT NULL,min_amount " + number
                    + " NOT NULL,early_penalty_rate " + number + " NOT NULL,active INTEGER NOT NULL DEFAULT 1,"
                    + "created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,PRIMARY KEY(bank_id,product_code))");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_term_deposits ("
                    + "id VARCHAR(128) PRIMARY KEY,bank_id VARCHAR(128) NOT NULL,player_uuid VARCHAR(36) NOT NULL,"
                    + "product_code VARCHAR(32) NOT NULL,principal " + number + " NOT NULL,fixed_rate " + number
                    + " NOT NULL,term_days INTEGER NOT NULL,opened_at BIGINT NOT NULL,matures_at BIGINT NOT NULL,"
                    + "auto_renew INTEGER NOT NULL DEFAULT 0,accrued_interest " + number
                    + " NOT NULL DEFAULT 0,status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',redeemed_at BIGINT,"
                    + "version BIGINT NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_loan_schedules ("
                    + "loan_id VARCHAR(128) NOT NULL,installment_no INTEGER NOT NULL,due_at BIGINT NOT NULL,"
                    + "principal_due " + number + " NOT NULL,interest_due " + number + " NOT NULL,paid_amount "
                    + number + " NOT NULL DEFAULT 0,status VARCHAR(32) NOT NULL DEFAULT 'PENDING',paid_at BIGINT,"
                    + "PRIMARY KEY(loan_id,installment_no))");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_risk_state ("
                    + "bank_id VARCHAR(128) PRIMARY KEY,retained_earnings " + number + " NOT NULL DEFAULT 0,"
                    + "loan_loss_provision " + number + " NOT NULL DEFAULT 0,insured_deposits " + number
                    + " NOT NULL DEFAULT 0,liquidity_support " + number + " NOT NULL DEFAULT 0,"
                    + "risk_rating VARCHAR(8) NOT NULL DEFAULT 'A',operating_status VARCHAR(32) NOT NULL DEFAULT 'NORMAL',"
                    + "last_assessed_at BIGINT NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_dividend_batches ("
                    + "id VARCHAR(128) PRIMARY KEY,bank_id VARCHAR(128) NOT NULL,amount " + number
                    + " NOT NULL,recipient_count INTEGER NOT NULL,status VARCHAR(32) NOT NULL,"
                    + "declared_by VARCHAR(36) NOT NULL,declared_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_dividend_payouts ("
                    + "batch_id VARCHAR(128) NOT NULL,player_uuid VARCHAR(36) NOT NULL,amount " + number
                    + " NOT NULL,PRIMARY KEY(batch_id,player_uuid))");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_restructure_requests ("
                    + "id VARCHAR(128) PRIMARY KEY,loan_id VARCHAR(128) NOT NULL,bank_id VARCHAR(128) NOT NULL,"
                    + "borrower_uuid VARCHAR(36) NOT NULL,requested_days INTEGER NOT NULL,quoted_fee " + number
                    + " NOT NULL,status VARCHAR(32) NOT NULL DEFAULT 'PENDING',requested_at BIGINT NOT NULL,"
                    + "decided_at BIGINT,decided_by VARCHAR(36))");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_policy_events ("
                    + "id VARCHAR(128) PRIMARY KEY,event_type VARCHAR(64) NOT NULL,title VARCHAR(128) NOT NULL,"
                    + "description VARCHAR(1024) NOT NULL,rate_modifier " + number + " NOT NULL DEFAULT 0,"
                    + "risk_modifier " + number + " NOT NULL DEFAULT 0,starts_at BIGINT NOT NULL,ends_at BIGINT NOT NULL,"
                    + "status VARCHAR(32) NOT NULL DEFAULT 'SCHEDULED',created_by VARCHAR(36),created_at BIGINT NOT NULL)");
        }
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_bank_term_owner_status",
                "ks_bank_term_deposits", "player_uuid", "status", "matures_at");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_bank_term_bank_status",
                "ks_bank_term_deposits", "bank_id", "status");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_bank_schedule_due",
                "ks_bank_loan_schedules", "status", "due_at");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_bank_policy_event_window",
                "ks_bank_policy_events", "status", "starts_at", "ends_at");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_bank_dividend_bank",
                "ks_bank_dividend_batches", "bank_id", "status", "declared_at");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_bank_restructure_bank",
                "ks_bank_restructure_requests", "bank_id", "status", "requested_at");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_loan_requests", "product_type",
                "VARCHAR(32) NOT NULL DEFAULT 'STANDARD'");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_loan_requests", "repayment_type",
                "VARCHAR(32) NOT NULL DEFAULT 'BULLET'");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_loan_requests", "purpose",
                "VARCHAR(256)");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_loan_requests", "quote_expires_at", "BIGINT");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_loans", "product_type",
                "VARCHAR(32) NOT NULL DEFAULT 'STANDARD'");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_loans", "repayment_type",
                "VARCHAR(32) NOT NULL DEFAULT 'BULLET'");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_loans", "purpose", "VARCHAR(256)");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_loans", "restructure_count",
                "INTEGER NOT NULL DEFAULT 0");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_loans", "grace_until", "BIGINT");
        PlayerLoanCollateralStore.initialize(connection);
    }

    static void ensureAccessTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            ensureAccessTables(connection, statement);
        }
    }

    static void ensureMoneySupplyTable(Connection connection) throws SQLException {
        DatabaseDialect dialect = BusinessSchemaDialect.detect(connection);
        String number = BusinessSchemaDialect.floatingPointType(dialect);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_money_supply (id " + generatedId(dialect)
                    + ",m0 " + number + " NOT NULL,m1 " + number + " NOT NULL,m2 " + number
                    + " NOT NULL,snapshot_at BIGINT NOT NULL)");
        }
    }

    static void initializeEnterpriseFinance(Connection connection) throws SQLException {
        DatabaseDialect dialect = BusinessSchemaDialect.detect(connection);
        String number = BusinessSchemaDialect.floatingPointType(dialect);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_enterprise_loan_requests ("
                    + "id VARCHAR(128) PRIMARY KEY,bank_id VARCHAR(128) NOT NULL,enterprise_id VARCHAR(128) NOT NULL,"
                    + "requester_uuid VARCHAR(36) NOT NULL,purpose VARCHAR(64) NOT NULL,principal " + number
                    + " NOT NULL,term_days INTEGER NOT NULL,collateral_type VARCHAR(64) NOT NULL,"
                    + "collateral_ref VARCHAR(512) NOT NULL,collateral_value " + number + " NOT NULL,loan_to_value "
                    + number + " NOT NULL,status VARCHAR(32) NOT NULL DEFAULT 'PENDING',requested_at BIGINT NOT NULL,"
                    + "decided_at BIGINT,loan_id VARCHAR(128))");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_enterprise_loans ("
                    + "id VARCHAR(128) PRIMARY KEY,bank_id VARCHAR(128) NOT NULL,enterprise_id VARCHAR(128) NOT NULL,"
                    + "purpose VARCHAR(64) NOT NULL,principal " + number + " NOT NULL,remaining " + number
                    + " NOT NULL,interest_rate " + number + " NOT NULL,term_days INTEGER NOT NULL,issued_at BIGINT NOT NULL,"
                    + "due_at BIGINT NOT NULL,overdue_at BIGINT DEFAULT 0,default_at BIGINT DEFAULT 0,"
                    + "status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE')");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_collateral ("
                    + "id VARCHAR(128) PRIMARY KEY,loan_id VARCHAR(128),enterprise_id VARCHAR(128) NOT NULL,"
                    + "bank_id VARCHAR(128) NOT NULL,asset_type VARCHAR(64) NOT NULL,asset_ref VARCHAR(512) NOT NULL,"
                    + "appraised_value " + number + " NOT NULL,status VARCHAR(32) NOT NULL DEFAULT 'LOCKED',"
                    + "locked_at BIGINT NOT NULL,released_at BIGINT DEFAULT 0,UNIQUE(asset_type,asset_ref,status))");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_collateral_auctions ("
                    + "id VARCHAR(128) PRIMARY KEY,collateral_id VARCHAR(128) NOT NULL,bank_id VARCHAR(128) NOT NULL,"
                    + "asset_type VARCHAR(64) NOT NULL,asset_ref VARCHAR(512) NOT NULL,starting_price " + number
                    + " NOT NULL,current_price " + number + " NOT NULL,highest_bidder_uuid VARCHAR(36),"
                    + "highest_escrow_id VARCHAR(128),buyer_enterprise_id VARCHAR(128),"
                    + "status VARCHAR(32) NOT NULL DEFAULT 'OPEN',opens_at BIGINT NOT NULL,closes_at BIGINT NOT NULL,"
                    + "settled_at BIGINT DEFAULT 0,version BIGINT NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_ent_inventory_lots ("
                    + "id VARCHAR(128) PRIMARY KEY,enterprise_id VARCHAR(128) NOT NULL,description VARCHAR(1024) NOT NULL,"
                    + "quantity INTEGER NOT NULL DEFAULT 1,appraised_value " + number
                    + " NOT NULL,status VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE',created_at BIGINT NOT NULL,"
                    + "updated_at BIGINT NOT NULL)");
        }
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_ent_loan_status",
                "ks_bank_enterprise_loans", "status", "due_at");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_ent_loan_ent",
                "ks_bank_enterprise_loans", "enterprise_id", "status");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_collateral_asset",
                "ks_bank_collateral", "asset_type", "asset_ref");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_collateral_auctions",
                "highest_escrow_id", "VARCHAR(128)");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_collateral_auctions",
                "buyer_enterprise_id", "VARCHAR(128)");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_collateral_auctions",
                "version", "BIGINT NOT NULL DEFAULT 0");
    }

    private static void ensureAccessTables(Connection connection, Statement statement) throws SQLException {
        statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_role_permissions (bank_id VARCHAR(128) NOT NULL,"
                + "role VARCHAR(32) NOT NULL,permission VARCHAR(64) NOT NULL,PRIMARY KEY(bank_id,role,permission))");
        statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_permissions (bank_id VARCHAR(128) NOT NULL,"
                + "player_uuid VARCHAR(36) NOT NULL,permission VARCHAR(64) NOT NULL,granted_by VARCHAR(36),"
                + "granted_at BIGINT NOT NULL,PRIMARY KEY(bank_id,player_uuid,permission))");
    }

    private static String generatedId(DatabaseDialect dialect) {
        return switch (dialect) {
            case SQLITE -> "INTEGER PRIMARY KEY AUTOINCREMENT";
            case MYSQL, MARIADB -> "BIGINT AUTO_INCREMENT PRIMARY KEY";
            case POSTGRESQL -> "BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY";
            case H2, UNKNOWN -> "BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY";
        };
    }

    private static void migrateAccountIds(Connection connection) throws SQLException {
        List<AccountId> migrations = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT id,bank_id,player_uuid FROM ks_bank_accounts")) {
            while (rows.next()) {
                String current = rows.getString(1);
                String target = rows.getString(2) + ":" + rows.getString(3);
                if (!target.equals(current)) migrations.add(new AccountId(current, target));
            }
        }
        for (AccountId migration : migrations) {
            boolean ownTransaction = connection.getAutoCommit();
            if (ownTransaction) connection.setAutoCommit(false);
            Savepoint savepoint = connection.setSavepoint();
            try {
                if (accountExists(connection, migration.target())) {
                    connection.rollback(savepoint);
                    if (ownTransaction) connection.commit();
                    continue;
                }
                try (PreparedStatement account = connection.prepareStatement(
                        "UPDATE ks_bank_accounts SET id=? WHERE id=?")) {
                    account.setString(1, migration.target());
                    account.setString(2, migration.current());
                    if (account.executeUpdate() != 1) {
                        connection.rollback(savepoint);
                        if (ownTransaction) connection.commit();
                        continue;
                    }
                }
                updateAccountReference(connection, "ks_bank_interest_state", migration);
                updateAccountReference(connection, "ks_bank_interest_postings", migration);
                connection.releaseSavepoint(savepoint);
                if (ownTransaction) connection.commit();
            } catch (SQLException failure) {
                connection.rollback(savepoint);
                if (ownTransaction) connection.commit();
                if (!accountExists(connection, migration.target())) throw failure;
            } finally {
                if (ownTransaction) connection.setAutoCommit(true);
            }
        }
    }

    private static boolean accountExists(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM ks_bank_accounts WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static void updateAccountReference(Connection connection, String table, AccountId migration)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + table + " SET account_id=? WHERE account_id=?")) {
            statement.setString(1, migration.target());
            statement.setString(2, migration.current());
            statement.executeUpdate();
        }
    }

    private static void migrateLegacyConfigColumns(Connection connection, DatabaseDialect dialect, String table)
            throws SQLException {
        renameColumnIfNeeded(connection, dialect, table, "key", "config_key");
        renameColumnIfNeeded(connection, dialect, table, "value", "config_value");
    }

    private static void renameColumnIfNeeded(Connection connection, DatabaseDialect dialect, String table,
                                             String oldName, String newName) throws SQLException {
        if (!columnExists(connection, table, oldName) || columnExists(connection, table, newName)) return;
        String quotedOld = switch (dialect) {
            case MYSQL, MARIADB -> "`" + oldName + "`";
            default -> "\"" + oldName + "\"";
        };
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " RENAME COLUMN " + quotedOld + " TO " + newName);
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
            var metadata = rows.getMetaData();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                if (column.equalsIgnoreCase(metadata.getColumnName(index))) return true;
            }
            return false;
        }
    }

    private record AccountId(String current, String target) {
    }
}
