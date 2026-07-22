package org.kseco.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/** Portable schema for the tables required before ks-Eco managers and extras start. */
public final class CoreBusinessSchema {
    private CoreBusinessSchema() {
    }

    public static void initialize(Connection connection) throws SQLException {
        initialize(connection, BusinessSchemaDialect.detect(connection));
    }

    static void initialize(Connection connection, DatabaseDialect dialect) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(dialect, "dialect");
        String real = BusinessSchemaDialect.floatingPointType(dialect);
        String identity = BusinessSchemaDialect.identityPrimaryKey(dialect);

        try (Statement statement = connection.createStatement()) {
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_eco_settings ("
                    + "config_key VARCHAR(128) PRIMARY KEY,config_value VARCHAR(2048) NOT NULL,updated_at BIGINT NOT NULL)");
            BusinessSchemaDialect.renameColumnIfPresent(connection, "ks_eco_settings", "key", "config_key");
            BusinessSchemaDialect.renameColumnIfPresent(connection, "ks_eco_settings", "value", "config_value");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_eco_transport_log ("
                    + "id " + identity + ",sender_uuid VARCHAR(36) NOT NULL,target_uuid VARCHAR(36) NOT NULL,"
                    + "item_material VARCHAR(128) NOT NULL,quantity INTEGER NOT NULL,source_world VARCHAR(128) NOT NULL,"
                    + "target_world VARCHAR(128) NOT NULL,distance " + real + " NOT NULL,cross_world INTEGER NOT NULL,"
                    + "fee " + real + " NOT NULL,created_at BIGINT NOT NULL)");

            execute(statement, "CREATE TABLE IF NOT EXISTS ks_ent_enterprises ("
                    + "id VARCHAR(64) PRIMARY KEY,name VARCHAR(128) NOT NULL,type VARCHAR(32) NOT NULL DEFAULT 'PRIVATE',"
                    + "owner_uuids TEXT NOT NULL,registered_capital " + real + " NOT NULL,current_assets " + real
                    + " DEFAULT 0,employee_count INTEGER DEFAULT 0,region VARCHAR(128),status VARCHAR(32) DEFAULT 'ACTIVE',"
                    + "created_at BIGINT NOT NULL)");
            BusinessSchemaDialect.addColumnIfMissing(connection, "ks_ent_enterprises", "dividend_rate",
                    real + " NOT NULL DEFAULT 0");
            BusinessSchemaDialect.addColumnIfMissing(connection, "ks_ent_enterprises", "description",
                    "VARCHAR(2048) NOT NULL DEFAULT ''");
            // Blind-box pricing and the Web enterprise API query this during manager startup,
            // before the later Web-only schema migration has a chance to run.
            BusinessSchemaDialect.addColumnIfMissing(connection, "ks_ent_enterprises", "industry",
                    "VARCHAR(64) NOT NULL DEFAULT 'OTHER'");
            BusinessSchemaDialect.addColumnIfMissing(connection, "ks_ent_enterprises", "level",
                    "INTEGER NOT NULL DEFAULT 1");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_ent_capital_injections ("
                    + "id VARCHAR(64) PRIMARY KEY,enterprise_id VARCHAR(64) NOT NULL,contributor_uuid VARCHAR(36) NOT NULL,"
                    + "amount " + real + " NOT NULL,injected_at BIGINT NOT NULL)");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_ent_dividend_shares ("
                    + "enterprise_id VARCHAR(64) NOT NULL,owner_uuid VARCHAR(36) NOT NULL,share_percent " + real
                    + " NOT NULL,updated_at BIGINT NOT NULL,PRIMARY KEY(enterprise_id,owner_uuid))");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_ent_members ("
                    + "enterprise_id VARCHAR(64) NOT NULL,player_uuid VARCHAR(36) NOT NULL,player_name VARCHAR(128) DEFAULT '',"
                    + "role VARCHAR(32) DEFAULT 'EMPLOYEE',salary " + real + " DEFAULT 0,joined_at BIGINT NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY(enterprise_id,player_uuid))");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_ent_join_requests ("
                    + "id VARCHAR(64) PRIMARY KEY,enterprise_id VARCHAR(64) NOT NULL,applicant_uuid VARCHAR(36) NOT NULL,"
                    + "applicant_name VARCHAR(128) DEFAULT '',status VARCHAR(32) NOT NULL DEFAULT 'PENDING',"
                    + "created_at BIGINT NOT NULL,reviewed_by VARCHAR(36),reviewed_at BIGINT DEFAULT 0,"
                    + "UNIQUE(enterprise_id,applicant_uuid))");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_ent_projects ("
                    + "id VARCHAR(64) PRIMARY KEY,title VARCHAR(255) NOT NULL,publisher_uuid VARCHAR(64) NOT NULL,"
                    + "publisher_type VARCHAR(32) NOT NULL DEFAULT 'OFFICIAL',budget " + real + " NOT NULL,"
                    + "prepayment_ratio " + real + " DEFAULT 0.3,penalty_ratio " + real + " DEFAULT 0.1,"
                    + "deadline BIGINT NOT NULL,location VARCHAR(512),allow_subcontract INTEGER DEFAULT 1,"
                    + "allow_consortium INTEGER DEFAULT 1,status VARCHAR(32) DEFAULT 'OPEN',created_at BIGINT NOT NULL)");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_ent_bids ("
                    + "id VARCHAR(64) PRIMARY KEY,project_id VARCHAR(64) NOT NULL,enterprise_id VARCHAR(64) NOT NULL,"
                    + "bid_amount " + real + " NOT NULL,is_consortium INTEGER DEFAULT 0,consortium_members TEXT,"
                    + "status VARCHAR(32) DEFAULT 'PENDING',submitted_at BIGINT NOT NULL,"
                    + "FOREIGN KEY(project_id) REFERENCES ks_ent_projects(id))");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_ent_dividends ("
                    + "id VARCHAR(64) PRIMARY KEY,enterprise_id VARCHAR(64) NOT NULL,amount " + real + " NOT NULL,"
                    + "declared_at BIGINT NOT NULL,tax_rate " + real + " DEFAULT 0.1,tax_paid " + real
                    + " DEFAULT 0,status VARCHAR(32) DEFAULT 'PAID')");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_ent_dividend_payouts ("
                    + "id VARCHAR(64) PRIMARY KEY,dividend_id VARCHAR(64) NOT NULL,enterprise_id VARCHAR(64) NOT NULL,"
                    + "recipient_uuid VARCHAR(36) NOT NULL,share_percent " + real + " NOT NULL,gross_amount " + real
                    + " NOT NULL,tax_amount " + real + " NOT NULL,net_amount " + real + " NOT NULL,paid_at BIGINT NOT NULL)");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_ent_invites ("
                    + "id VARCHAR(64) PRIMARY KEY,enterprise_id VARCHAR(64),bank_id VARCHAR(64),"
                    + "inviter_uuid VARCHAR(36) NOT NULL,invitee_uuid VARCHAR(36) NOT NULL,status VARCHAR(32) DEFAULT 'PENDING',"
                    + "created_at BIGINT NOT NULL,responded_at BIGINT DEFAULT 0)");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_ent_pending_creations ("
                    + "id VARCHAR(64) PRIMARY KEY,creator_uuid VARCHAR(36) NOT NULL,name VARCHAR(128) NOT NULL,"
                    + "type VARCHAR(32) NOT NULL,owner_uuids TEXT NOT NULL,registered_capital " + real + " NOT NULL,"
                    + "region VARCHAR(128) DEFAULT '',status VARCHAR(32) NOT NULL DEFAULT 'PENDING',created_at BIGINT NOT NULL,"
                    + "expires_at BIGINT NOT NULL,finalized_enterprise_id VARCHAR(64))");
            BusinessSchemaDialect.addColumnIfMissing(connection, "ks_ent_pending_creations", "kind",
                    "VARCHAR(32) NOT NULL DEFAULT 'ENTERPRISE'");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_ent_pending_creation_confirmations ("
                    + "pending_id VARCHAR(64) NOT NULL,player_uuid VARCHAR(36) NOT NULL,"
                    + "status VARCHAR(32) NOT NULL DEFAULT 'PENDING',responded_at BIGINT DEFAULT 0,"
                    + "PRIMARY KEY(pending_id,player_uuid))");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_ent_permissions ("
                    + "enterprise_id VARCHAR(64) NOT NULL,player_uuid VARCHAR(36) NOT NULL,permission VARCHAR(128) NOT NULL,"
                    + "granted_by VARCHAR(36),granted_at BIGINT NOT NULL,PRIMARY KEY(enterprise_id,player_uuid,permission))");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_ent_role_permissions ("
                    + "enterprise_id VARCHAR(64) NOT NULL,role VARCHAR(32) NOT NULL,permission VARCHAR(128) NOT NULL,"
                    + "PRIMARY KEY(enterprise_id,role,permission))");

            execute(statement, "CREATE TABLE IF NOT EXISTS ks_tax_records ("
                    + "id VARCHAR(64) PRIMARY KEY,payer_uuid VARCHAR(36) NOT NULL,payer_name VARCHAR(128),"
                    + "category VARCHAR(128) NOT NULL,base_amount " + real + " NOT NULL,tax_rate " + real
                    + " NOT NULL,tax_amount " + real + " NOT NULL,description TEXT,collected_at BIGINT NOT NULL)");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_tax_rates ("
                    + "category VARCHAR(128) PRIMARY KEY,rate " + real + " NOT NULL,updated_at BIGINT NOT NULL)");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_tax_penalties ("
                    + "id VARCHAR(64) PRIMARY KEY,target_uuid VARCHAR(36) NOT NULL,target_name VARCHAR(128),"
                    + "penalty_type VARCHAR(128) NOT NULL,base_amount " + real + " NOT NULL,penalty_rate " + real
                    + " NOT NULL DEFAULT 0.2,penalty_amount " + real + " NOT NULL,reason TEXT,paid INTEGER NOT NULL DEFAULT 0,"
                    + "issued_at BIGINT NOT NULL)");

            execute(statement, "CREATE TABLE IF NOT EXISTS ks_bank_banks ("
                    + "id VARCHAR(64) PRIMARY KEY,name VARCHAR(128) NOT NULL,type VARCHAR(32) NOT NULL DEFAULT 'COMMERCIAL',"
                    + "owner_uuids TEXT NOT NULL,total_assets " + real + " DEFAULT 0,reserve_ratio " + real
                    + " DEFAULT 0.1,interest_rate " + real + " DEFAULT 0.03,loan_rate " + real
                    + " DEFAULT 0.08,status VARCHAR(32) DEFAULT 'ACTIVE',created_at BIGINT NOT NULL)");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_bank_accounts ("
                    + "id VARCHAR(128) PRIMARY KEY,bank_id VARCHAR(64) NOT NULL,player_uuid VARCHAR(36) NOT NULL,"
                    + "balance " + real + " DEFAULT 0,interest_earned " + real + " DEFAULT 0,opened_at BIGINT NOT NULL,"
                    + "FOREIGN KEY(bank_id) REFERENCES ks_bank_banks(id))");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_bank_loans ("
                    + "id VARCHAR(64) PRIMARY KEY,bank_id VARCHAR(64) NOT NULL,borrower_uuid VARCHAR(36) NOT NULL,"
                    + "principal " + real + " NOT NULL,remaining " + real + " NOT NULL,interest_rate " + real
                    + " NOT NULL,term_days INTEGER NOT NULL,issued_at BIGINT NOT NULL,due_at BIGINT NOT NULL,"
                    + "status VARCHAR(32) DEFAULT 'ACTIVE',FOREIGN KEY(bank_id) REFERENCES ks_bank_banks(id))");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_bank_cb_rates ("
                    + "id " + identity + ",base_rate " + real + " NOT NULL,reserve_requirement " + real
                    + " NOT NULL,set_at BIGINT NOT NULL)");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_bank_cb_config ("
                    + "config_key VARCHAR(128) PRIMARY KEY,config_value VARCHAR(2048) NOT NULL)");
            BusinessSchemaDialect.renameColumnIfPresent(connection, "ks_bank_cb_config", "key", "config_key");
            BusinessSchemaDialect.renameColumnIfPresent(connection, "ks_bank_cb_config", "value", "config_value");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_bank_system_singletons ("
                    + "singleton_key VARCHAR(64) PRIMARY KEY,resource_id VARCHAR(64) NOT NULL,updated_at BIGINT NOT NULL)");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_bank_rates ("
                    + "bank_id VARCHAR(64) PRIMARY KEY,loan_rate " + real + " DEFAULT 0.05,deposit_rate " + real
                    + " DEFAULT 0.01,updated_at BIGINT NOT NULL DEFAULT 0)");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_bank_members ("
                    + "bank_id VARCHAR(64) NOT NULL,player_uuid VARCHAR(36) NOT NULL,player_name VARCHAR(128),"
                    + "role VARCHAR(32) DEFAULT 'MEMBER',joined_at BIGINT NOT NULL DEFAULT 0,PRIMARY KEY(bank_id,player_uuid))");

            // Dormant legacy tables are retained so a complete SQLite -> shared-JDBC
            // migration does not silently drop historical rows. They are not reactivated.
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_dungeon_grids ("
                    + "id VARCHAR(64) PRIMARY KEY,world VARCHAR(128) NOT NULL,grid_x INTEGER NOT NULL,grid_z INTEGER NOT NULL,"
                    + "status VARCHAR(32) NOT NULL DEFAULT 'FREE',occupied_since BIGINT DEFAULT 0,last_used_at BIGINT DEFAULT 0,"
                    + "UNIQUE(world,grid_x,grid_z))");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_bb_tickets ("
                    + "id VARCHAR(64) PRIMARY KEY,enterprise_id VARCHAR(64) NOT NULL,pool_id VARCHAR(64) NOT NULL,"
                    + "quantity INTEGER NOT NULL DEFAULT 1,remaining INTEGER NOT NULL DEFAULT 1,"
                    + "source VARCHAR(32) DEFAULT 'PURCHASE',expires_at BIGINT DEFAULT 0,created_at BIGINT NOT NULL)");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_bb_ticket_log ("
                    + "id " + identity + ",ticket_id VARCHAR(64) NOT NULL,enterprise_id VARCHAR(64) NOT NULL,"
                    + "pool_id VARCHAR(64) NOT NULL,drawn_by_uuid VARCHAR(36) NOT NULL,item_material VARCHAR(128) NOT NULL,"
                    + "rarity VARCHAR(32) NOT NULL,drawn_at BIGINT NOT NULL)");
            execute(statement, "CREATE TABLE IF NOT EXISTS ks_re_taxes ("
                    + "id VARCHAR(64) PRIMARY KEY,plot_id VARCHAR(64) NOT NULL,amount " + real + " NOT NULL,"
                    + "due_at BIGINT NOT NULL,paid INTEGER NOT NULL DEFAULT 0)");
        }

        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_ent_join_requests_status",
                "ks_ent_join_requests", "enterprise_id", "status", "created_at");
        PriceEngineSchema.initialize(connection);
        EconomicFeatureSchema.initialize(connection);
        CompensationSchema.initialize(connection);
    }

    private static void execute(Statement statement, String sql) throws SQLException {
        statement.executeUpdate(sql);
    }
}
