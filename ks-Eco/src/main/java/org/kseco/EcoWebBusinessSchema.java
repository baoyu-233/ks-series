package org.kseco;

import org.kseco.database.BusinessSchemaDialect;
import org.kseco.database.DatabaseDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Portable schema boundary for the legacy business tables used directly by the Web API. */
final class EcoWebBusinessSchema {
    private EcoWebBusinessSchema() { }

    static void initialize(Connection connection) throws SQLException {
        initialize(connection, BusinessSchemaDialect.detect(connection));
    }

    static void initialize(Connection connection, DatabaseDialect dialect) throws SQLException {
        String number = BusinessSchemaDialect.floatingPointType(dialect);
        String binary = BusinessSchemaDialect.binaryType(dialect);
        String identity = BusinessSchemaDialect.identityPrimaryKey(dialect);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_banks (id VARCHAR(128) PRIMARY KEY, name VARCHAR(128) NOT NULL, type VARCHAR(32) NOT NULL DEFAULT 'COMMERCIAL', owner_uuids VARCHAR(4096) NOT NULL, total_assets " + number + " DEFAULT 0.0, reserve_ratio " + number + " DEFAULT 0.1, interest_rate " + number + " DEFAULT 0.03, loan_rate " + number + " DEFAULT 0.08, status VARCHAR(32) DEFAULT 'ACTIVE', created_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_accounts (id VARCHAR(128) PRIMARY KEY, bank_id VARCHAR(128) NOT NULL, player_uuid VARCHAR(36) NOT NULL, balance " + number + " DEFAULT 0.0, interest_earned " + number + " DEFAULT 0.0, opened_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_loans (id VARCHAR(128) PRIMARY KEY, bank_id VARCHAR(128) NOT NULL, borrower_uuid VARCHAR(36) NOT NULL, principal " + number + " NOT NULL, remaining " + number + " NOT NULL, interest_rate " + number + " NOT NULL, term_days INTEGER NOT NULL, issued_at BIGINT NOT NULL, due_at BIGINT NOT NULL, status VARCHAR(32) DEFAULT 'ACTIVE')");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_cb_rates (id " + identity + ", base_rate " + number + " NOT NULL, reserve_requirement " + number + " NOT NULL, set_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_cb_config (config_key VARCHAR(128) PRIMARY KEY, config_value VARCHAR(2048) NOT NULL)");
            createBankRates(statement, number);
            createBankMembers(statement);
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_permissions (bank_id VARCHAR(128) NOT NULL, player_uuid VARCHAR(36) NOT NULL, permission VARCHAR(128) NOT NULL, granted_by VARCHAR(36), granted_at BIGINT NOT NULL, PRIMARY KEY(bank_id, player_uuid, permission))");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_money_supply (id " + identity + ", m0 " + number + " NOT NULL, m1 " + number + " NOT NULL, m2 " + number + " NOT NULL, snapshot_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_eco_settings (config_key VARCHAR(128) PRIMARY KEY, config_value VARCHAR(2048) NOT NULL, updated_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_loan_requests (id VARCHAR(128) PRIMARY KEY, bank_id VARCHAR(128) NOT NULL, borrower_uuid VARCHAR(36) NOT NULL, borrower_name VARCHAR(128), principal " + number + " NOT NULL, term_days INTEGER NOT NULL, status VARCHAR(32) DEFAULT 'PENDING', requested_at BIGINT NOT NULL, decided_at BIGINT, loan_id VARCHAR(128))");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_guidance_config (config_key VARCHAR(128) PRIMARY KEY, config_value VARCHAR(2048) NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_guidance_claims (player_uuid VARCHAR(36) PRIMARY KEY, loan_id VARCHAR(128) NOT NULL, claimed_at BIGINT NOT NULL)");

            statement.execute("CREATE TABLE IF NOT EXISTS ks_ent_enterprises (id VARCHAR(128) PRIMARY KEY, name VARCHAR(128) NOT NULL, description VARCHAR(2048) DEFAULT '', type VARCHAR(32) NOT NULL DEFAULT 'PRIVATE', owner_uuids VARCHAR(4096) NOT NULL, registered_capital " + number + " DEFAULT 0, current_assets " + number + " DEFAULT 0, employee_count INTEGER DEFAULT 0, region VARCHAR(256) DEFAULT '', status VARCHAR(32) DEFAULT 'ACTIVE', created_at BIGINT NOT NULL)");
            createEnterpriseMembers(statement, number);
            statement.execute("CREATE TABLE IF NOT EXISTS ks_ent_projects (id VARCHAR(128) PRIMARY KEY, title VARCHAR(256) NOT NULL, publisher_uuid VARCHAR(128) NOT NULL, publisher_type VARCHAR(32) NOT NULL DEFAULT 'OFFICIAL', budget " + number + " NOT NULL, prepayment_ratio " + number + " DEFAULT 0.3, penalty_ratio " + number + " DEFAULT 0.1, deposit_ratio " + number + " DEFAULT 0, deposit_deadline_hours INTEGER DEFAULT 24, deadline BIGINT NOT NULL, location VARCHAR(512) DEFAULT '', allow_subcontract INTEGER DEFAULT 1, allow_consortium INTEGER DEFAULT 1, status VARCHAR(32) DEFAULT 'OPEN', created_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_ent_project_escrow (project_id VARCHAR(128) PRIMARY KEY, publisher_type VARCHAR(32) NOT NULL, publisher_ref VARCHAR(128) NOT NULL, remaining " + number + " NOT NULL, created_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_ent_bids (id VARCHAR(128) PRIMARY KEY, project_id VARCHAR(128) NOT NULL, enterprise_id VARCHAR(128), bidder_uuid VARCHAR(36), bidder_type VARCHAR(32) DEFAULT 'ENTERPRISE', bid_amount " + number + " NOT NULL, is_consortium INTEGER DEFAULT 0, consortium_members VARCHAR(4096) DEFAULT '', status VARCHAR(32) DEFAULT 'PENDING', deposit_amount " + number + " DEFAULT 0, deposit_deadline BIGINT DEFAULT 0, deposit_paid_at BIGINT DEFAULT 0, submitted_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_ent_bid_deposits (id VARCHAR(128) PRIMARY KEY, bid_id VARCHAR(128) NOT NULL, project_id VARCHAR(128) NOT NULL, payer_uuid VARCHAR(36), payer_enterprise_id VARCHAR(128), amount " + number + " NOT NULL, status VARCHAR(32) DEFAULT 'HELD', paid_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_ent_dividends (id VARCHAR(128) PRIMARY KEY, enterprise_id VARCHAR(128) NOT NULL, amount " + number + " NOT NULL, declared_at BIGINT NOT NULL, tax_rate " + number + " DEFAULT 0, tax_paid " + number + " DEFAULT 0, status VARCHAR(32) DEFAULT 'PAID')");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_ent_dividend_payouts (id VARCHAR(128) PRIMARY KEY, dividend_id VARCHAR(128) NOT NULL, enterprise_id VARCHAR(128) NOT NULL, recipient_uuid VARCHAR(36) NOT NULL, share_percent " + number + " NOT NULL, gross_amount " + number + " NOT NULL, tax_amount " + number + " NOT NULL, net_amount " + number + " NOT NULL, paid_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_ent_invites (id VARCHAR(128) PRIMARY KEY, enterprise_id VARCHAR(128), bank_id VARCHAR(128), inviter_uuid VARCHAR(36) NOT NULL, invitee_uuid VARCHAR(36) NOT NULL, status VARCHAR(32) DEFAULT 'PENDING', created_at BIGINT NOT NULL, responded_at BIGINT)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_ent_pending_creations (id VARCHAR(128) PRIMARY KEY, creator_uuid VARCHAR(36) NOT NULL, name VARCHAR(128) NOT NULL, type VARCHAR(32) NOT NULL, owner_uuids VARCHAR(4096) NOT NULL, registered_capital " + number + " NOT NULL, region VARCHAR(256) DEFAULT '', status VARCHAR(32) NOT NULL DEFAULT 'PENDING', created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, finalized_enterprise_id VARCHAR(128), kind VARCHAR(32) NOT NULL DEFAULT 'ENTERPRISE')");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_ent_pending_creation_confirmations (pending_id VARCHAR(128) NOT NULL, player_uuid VARCHAR(36) NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'PENDING', responded_at BIGINT DEFAULT 0, PRIMARY KEY (pending_id, player_uuid))");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_ent_permissions (enterprise_id VARCHAR(128) NOT NULL, player_uuid VARCHAR(36) NOT NULL, permission VARCHAR(128) NOT NULL, granted_by VARCHAR(36), granted_at BIGINT NOT NULL, PRIMARY KEY(enterprise_id,player_uuid,permission))");

            statement.execute("CREATE TABLE IF NOT EXISTS ks_tax_records (id VARCHAR(128) PRIMARY KEY, payer_uuid VARCHAR(36) NOT NULL, payer_name VARCHAR(128) DEFAULT '', category VARCHAR(128) DEFAULT '', base_amount " + number + " DEFAULT 0, tax_rate " + number + " DEFAULT 0, tax_amount " + number + " DEFAULT 0, description VARCHAR(2048) DEFAULT '', collected_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_tax_rates (category VARCHAR(128) PRIMARY KEY, rate " + number + " NOT NULL, updated_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_tax_penalties (id VARCHAR(128) PRIMARY KEY, target_uuid VARCHAR(36) NOT NULL, target_name VARCHAR(128) DEFAULT '', penalty_type VARCHAR(64) DEFAULT 'TAX_EVASION', base_amount " + number + " DEFAULT 0, penalty_rate " + number + " DEFAULT 0.2, penalty_amount " + number + " DEFAULT 0, reason VARCHAR(2048) DEFAULT '', paid INTEGER DEFAULT 0, issued_at BIGINT NOT NULL)");

            statement.execute("CREATE TABLE IF NOT EXISTS ks_official_prices (material VARCHAR(128) PRIMARY KEY, buy_price " + number + " DEFAULT 0, sell_price " + number + " DEFAULT 0, category VARCHAR(128) DEFAULT '', updated_at BIGINT DEFAULT 0)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_eco_trades (id VARCHAR(64) PRIMARY KEY, item_material VARCHAR(128) NOT NULL, item_signature VARCHAR(512), quantity INTEGER NOT NULL, unit_price " + number + " NOT NULL, buyer_uuid VARCHAR(36), seller_uuid VARCHAR(36), timestamp BIGINT NOT NULL, trade_type VARCHAR(64) DEFAULT 'PLAYER_TRADE')");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_builtin_economy (uuid VARCHAR(36) PRIMARY KEY, balance " + number + " DEFAULT 0, name VARCHAR(128) DEFAULT '', updated_at BIGINT DEFAULT 0)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_audit_log (id " + identity + ", action VARCHAR(128) NOT NULL, player_uuid VARCHAR(36) NOT NULL, player_name VARCHAR(128) DEFAULT '', target_type VARCHAR(128) DEFAULT '', target_id VARCHAR(128) DEFAULT '', details VARCHAR(4096) DEFAULT '', created_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_ent_corporate_accounts (enterprise_id VARCHAR(128) PRIMARY KEY, bank_id VARCHAR(128) NOT NULL, balance " + number + " DEFAULT 0.0, updated_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_ent_procurements (id VARCHAR(128) PRIMARY KEY, enterprise_id VARCHAR(128) NOT NULL, title VARCHAR(256) NOT NULL, item_desc VARCHAR(2048) DEFAULT '', quantity INTEGER DEFAULT 1, budget " + number + " NOT NULL, status VARCHAR(32) DEFAULT 'OPEN', created_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_ent_procurement_bids (id VARCHAR(128) PRIMARY KEY, procurement_id VARCHAR(128) NOT NULL, bidder_uuid VARCHAR(36), enterprise_id VARCHAR(128), bidder_type VARCHAR(32) DEFAULT 'ENTERPRISE', unit_price " + number + " NOT NULL, total_price " + number + " NOT NULL, status VARCHAR(32) DEFAULT 'PENDING', submitted_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bb_pools (id VARCHAR(128) PRIMARY KEY, name VARCHAR(128) NOT NULL, pool_type VARCHAR(32) NOT NULL DEFAULT 'ITEM', price " + number + " NOT NULL DEFAULT 100, enabled INTEGER DEFAULT 1, pity_max INTEGER DEFAULT 50, description VARCHAR(2048) DEFAULT '', owner_type VARCHAR(32) NOT NULL DEFAULT 'PUBLIC', allowed_categories VARCHAR(2048) DEFAULT '', allowed_industries VARCHAR(2048) DEFAULT '', created_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bb_loot (id VARCHAR(128) PRIMARY KEY, pool_id VARCHAR(128) NOT NULL, item_material VARCHAR(128) NOT NULL, item_data " + binary + ", display_name VARCHAR(256) DEFAULT '', weight INTEGER NOT NULL DEFAULT 1, rarity VARCHAR(32) NOT NULL DEFAULT 'COMMON', quantity INTEGER DEFAULT 1, created_at BIGINT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bb_pity (uuid VARCHAR(36) NOT NULL, pool_id VARCHAR(128) NOT NULL, count_since_rare INTEGER DEFAULT 0, updated_at BIGINT NOT NULL, PRIMARY KEY(uuid,pool_id))");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bb_pity_rarity (uuid VARCHAR(36) NOT NULL, pool_id VARCHAR(128) NOT NULL, rarity VARCHAR(32) NOT NULL, count_since_hit INTEGER DEFAULT 0, updated_at BIGINT NOT NULL, PRIMARY KEY(uuid,pool_id,rarity))");
            statement.execute("CREATE TABLE IF NOT EXISTS ks_bb_log (id " + identity + ", uuid VARCHAR(36) NOT NULL, pool_id VARCHAR(128) NOT NULL, item_material VARCHAR(128) NOT NULL, rarity VARCHAR(32) NOT NULL, pulled_at BIGINT NOT NULL)");
        }

        ProjectWalletSettlementStore.initialize(connection);
        addLegacyColumns(connection, number);
    }

    static void ensureBankRates(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            createBankRates(statement, BusinessSchemaDialect.floatingPointType(BusinessSchemaDialect.detect(connection)));
        }
    }

    static void ensureBankMembers(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            createBankMembers(statement);
        }
    }

    static void ensureEnterpriseMembers(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            createEnterpriseMembers(statement,
                    BusinessSchemaDialect.floatingPointType(BusinessSchemaDialect.detect(connection)));
        }
    }

    private static void createBankRates(Statement statement, String number) throws SQLException {
        statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_rates (bank_id VARCHAR(128) PRIMARY KEY, loan_rate " + number + " DEFAULT 0.05, deposit_rate " + number + " DEFAULT 0.01, updated_at BIGINT DEFAULT 0)");
    }

    private static void createBankMembers(Statement statement) throws SQLException {
        statement.execute("CREATE TABLE IF NOT EXISTS ks_bank_members (bank_id VARCHAR(128) NOT NULL, player_uuid VARCHAR(36) NOT NULL, player_name VARCHAR(128), role VARCHAR(32) DEFAULT 'MEMBER', joined_at BIGINT DEFAULT 0, PRIMARY KEY(bank_id,player_uuid))");
    }

    private static void createEnterpriseMembers(Statement statement, String number) throws SQLException {
        statement.execute("CREATE TABLE IF NOT EXISTS ks_ent_members (enterprise_id VARCHAR(128) NOT NULL, player_uuid VARCHAR(36) NOT NULL, player_name VARCHAR(128), role VARCHAR(32) DEFAULT 'EMPLOYEE', salary " + number + " DEFAULT 0, joined_at BIGINT DEFAULT 0, PRIMARY KEY(enterprise_id,player_uuid))");
    }

    private static void addLegacyColumns(Connection connection, String number) throws SQLException {
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_ent_pending_creations", "kind", "VARCHAR(32) NOT NULL DEFAULT 'ENTERPRISE'");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_ent_enterprises", "description", "VARCHAR(2048) DEFAULT ''");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_ent_bids", "bidder_uuid", "VARCHAR(36)");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_ent_bids", "bidder_type", "VARCHAR(32) DEFAULT 'ENTERPRISE'");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_ent_bids", "deposit_amount", number + " DEFAULT 0");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_ent_bids", "deposit_deadline", "BIGINT DEFAULT 0");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_ent_bids", "deposit_paid_at", "BIGINT DEFAULT 0");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_ent_projects", "deposit_ratio", number + " DEFAULT 0");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_ent_projects", "deposit_deadline_hours", "INTEGER DEFAULT 24");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_banks", "reserve_ratio", number + " DEFAULT 0.1");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_banks", "interest_rate", number + " DEFAULT 0.03");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bank_banks", "loan_rate", number + " DEFAULT 0.08");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bb_pools", "owner_type", "VARCHAR(32) NOT NULL DEFAULT 'PUBLIC'");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bb_pools", "allowed_categories", "VARCHAR(2048) DEFAULT ''");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bb_pools", "allowed_industries", "VARCHAR(2048) DEFAULT ''");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bb_pools", "pity_rules", "VARCHAR(4096) DEFAULT ''");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_bb_pools", "min_enterprise_level", "INTEGER NOT NULL DEFAULT 1");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_ent_enterprises", "industry", "VARCHAR(64) NOT NULL DEFAULT 'OTHER'");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_ent_enterprises", "level", "INTEGER NOT NULL DEFAULT 1");
    }
}
