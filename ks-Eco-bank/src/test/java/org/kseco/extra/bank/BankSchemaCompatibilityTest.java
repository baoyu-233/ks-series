package org.kseco.extra.bank;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.kseco.database.DatabaseDialect;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankSchemaCompatibilityTest {

    static Stream<Arguments> databaseModes() {
        return Stream.of(
                Arguments.of("MySQL", DatabaseDialect.MYSQL, "`key`", "`value`"),
                Arguments.of("PostgreSQL", DatabaseDialect.POSTGRESQL, "\"key\"", "\"value\"")
        );
    }

    @ParameterizedTest
    @MethodSource("databaseModes")
    void initializesCoreSchemaAndCoreMutationsInCompatibilityModes(
            String mode, DatabaseDialect dialect, String ignoredKey, String ignoredValue) throws Exception {
        try (Connection connection = open(mode)) {
            BankSchema.initialize(connection, dialect);
            BankSchema.initialize(connection, dialect);
            BankSchema.initializeEnterpriseFinance(connection);
            BankSchema.initializeEnterpriseFinance(connection);
            BankSchema.initializeGameplay(connection);
            BankSchema.initializeGameplay(connection);
            BankAuctionEscrowStore.initialize(connection, dialect);
            BankAuctionEscrowStore.initialize(connection, dialect);
            try (Statement statement = connection.createStatement()) {
                statement.execute(CentralBankLoanSchema.createTableSql(dialect));
            }

            long now = 1_720_000_000L;
            try (var bank = connection.prepareStatement(
                    "INSERT INTO ks_bank_banks (id,name,type,owner_uuids,total_assets,status,created_at) "
                            + "VALUES (?,?,?,?,?,'ACTIVE',?)")) {
                bank.setString(1, "BANK-A");
                bank.setString(2, "Bank A");
                bank.setString(3, "COMMERCIAL");
                bank.setString(4, UUID.randomUUID().toString());
                bank.setDouble(5, 10_000);
                bank.setLong(6, now);
                assertEquals(1, bank.executeUpdate());
            }
            try (var account = connection.prepareStatement(
                    "INSERT INTO ks_bank_accounts (id,bank_id,player_uuid,balance,opened_at) VALUES (?,?,?,?,?)")) {
                account.setString(1, "BANK-A:legacy");
                account.setString(2, "BANK-A");
                account.setString(3, "00000000-0000-0000-0000-000000000001");
                account.setDouble(4, 125.50);
                account.setLong(5, now);
                assertEquals(1, account.executeUpdate());
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO ks_bank_interest_state "
                        + "(account_id,bank_id,balance_minor,accrued_through,open_period_start,period_seconds,"
                        + "balance_time_minor_seconds,interest_numerator_minor_seconds,rate_per_period,version,updated_at) "
                        + "VALUES ('BANK-A:legacy','BANK-A',12550," + now + "," + now
                        + ",604800,'0','0','0.01',0," + now + ")");
                statement.executeUpdate("INSERT INTO ks_bank_interest_postings "
                        + "(bank_id,account_id,period_key,period_start,period_end,average_balance_minor,interest_minor,posted_at) "
                        + "VALUES ('BANK-A','BANK-A:legacy','period-1',1,2,'12550',1," + now + ")");
            }
            BankSchema.initialize(connection, dialect);
            assertEquals(125.50, scalarDouble(connection,
                    "SELECT balance FROM ks_bank_accounts WHERE id='BANK-A:00000000-0000-0000-0000-000000000001'"));
            assertEquals(1.0, scalarDouble(connection,
                    "SELECT COUNT(*) FROM ks_bank_interest_state "
                            + "WHERE account_id='BANK-A:00000000-0000-0000-0000-000000000001'"));
            assertEquals(1.0, scalarDouble(connection,
                    "SELECT COUNT(*) FROM ks_bank_interest_postings "
                            + "WHERE account_id='BANK-A:00000000-0000-0000-0000-000000000001'"));

            BankSqlMutation.upsert(connection,
                    "UPDATE ks_bank_cb_config SET config_value=? WHERE config_key=?", update -> {
                        update.setString(1, "0.04");
                        update.setString(2, "base_rate");
                    }, "INSERT INTO ks_bank_cb_config (config_key,config_value) VALUES (?,?)", insert -> {
                        insert.setString(1, "base_rate");
                        insert.setString(2, "0.04");
                    });
            BankSqlMutation.upsert(connection,
                    "UPDATE ks_bank_cb_config SET config_value=? WHERE config_key=?", update -> {
                        update.setString(1, "0.05");
                        update.setString(2, "base_rate");
                    }, "INSERT INTO ks_bank_cb_config (config_key,config_value) VALUES (?,?)", insert -> {
                        insert.setString(1, "base_rate");
                        insert.setString(2, "0.05");
                    });
            assertEquals("0.05", scalarString(connection,
                    "SELECT config_value FROM ks_bank_cb_config WHERE config_key='base_rate'"));
            assertTrue(columns(connection, "KS_BANK_TERM_DEPOSITS").contains("fixed_rate"));
            assertTrue(columns(connection, "KS_BANK_LOAN_REQUESTS").contains("product_type"));
            assertTrue(columns(connection, "KS_BANK_LOANS").contains("repayment_type"));

            try (Statement statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate(
                        "INSERT INTO ks_bank_money_supply (m0,m1,m2,snapshot_at) VALUES (1,2,3," + now + ")"));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("databaseModes")
    void migratesLegacyReservedConfigColumns(
            String mode, DatabaseDialect dialect, String quotedKey, String quotedValue) throws Exception {
        try (Connection connection = open(mode); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ks_bank_cb_config (" + quotedKey
                    + " VARCHAR(128) PRIMARY KEY," + quotedValue + " VARCHAR(2048) NOT NULL)");
            statement.execute("INSERT INTO ks_bank_cb_config (" + quotedKey + "," + quotedValue
                    + ") VALUES ('rate_min','0.01')");
            statement.execute("CREATE TABLE ks_bank_guidance_config (" + quotedKey
                    + " VARCHAR(128) PRIMARY KEY," + quotedValue + " VARCHAR(2048) NOT NULL)");
            statement.execute("INSERT INTO ks_bank_guidance_config (" + quotedKey + "," + quotedValue
                    + ") VALUES ('enabled','1')");

            BankSchema.initialize(connection, dialect);

            Set<String> cbColumns = columns(connection, "KS_BANK_CB_CONFIG");
            assertTrue(cbColumns.contains("config_key"));
            assertTrue(cbColumns.contains("config_value"));
            assertFalse(cbColumns.contains("key"));
            assertFalse(cbColumns.contains("value"));
            assertEquals("0.01", scalarString(connection,
                    "SELECT config_value FROM ks_bank_cb_config WHERE config_key='rate_min'"));
            assertEquals("1", scalarString(connection,
                    "SELECT config_value FROM ks_bank_guidance_config WHERE config_key='enabled'"));
        }
    }

    private static Connection open(String mode) throws Exception {
        return DriverManager.getConnection("jdbc:h2:mem:bank_" + mode + "_" + UUID.randomUUID()
                + ";MODE=" + mode + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    private static Set<String> columns(Connection connection, String table) throws Exception {
        var names = new java.util.HashSet<String>();
        try (ResultSet rows = connection.getMetaData().getColumns(null, null, table.toLowerCase(), null)) {
            while (rows.next()) names.add(rows.getString("COLUMN_NAME").toLowerCase());
        }
        return names;
    }

    private static String scalarString(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static double scalarDouble(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getDouble(1);
        }
    }
}
