package org.kseco.currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcCurrencyLedgerTest {
    @TempDir
    Path tempDirectory;

    @Test
    void treatsCaseDistinctOperationIdsAsDistinctOperations() throws Exception {
        JdbcCurrencyLedger ledger = sqliteLedger("operation-case.db");
        ledger.initializeSchema();
        CurrencyAccount account = CurrencyAccount.bank("Treasury");
        CurrencyId currency = CurrencyId.of("credit");

        LedgerResult first = ledger.apply(credit("BUY:A", account, currency, 10L));
        LedgerResult second = ledger.apply(credit("buy:a", account, currency, 10L));
        ledger.initializeSchema();
        LedgerResult replay = ledger.apply(credit("BUY:A", account, currency, 10L));

        assertEquals(LedgerResult.Outcome.APPLIED, first.outcome());
        assertFalse(first.replayed());
        assertEquals(LedgerResult.Outcome.APPLIED, second.outcome());
        assertFalse(second.replayed());
        assertTrue(replay.replayed());
        assertEquals(20L, ledger.balance(account, currency).minorUnits());
    }

    @Test
    void alignsJavaIdentifierCanonicalizationWithDatabaseKeys() throws Exception {
        JdbcCurrencyLedger ledger = sqliteLedger("account-case.db");
        ledger.initializeSchema();
        CurrencyAccount mixedCase = new CurrencyAccount(" bank ", "Treasury");
        CurrencyAccount lowerCase = CurrencyAccount.bank("treasury");
        CurrencyId lowerCurrency = CurrencyId.of(" credit ");
        CurrencyId upperCurrency = CurrencyId.of("CREDIT");

        assertEquals("BANK", mixedCase.type());
        assertEquals("Treasury", mixedCase.id());
        assertNotEquals(mixedCase, lowerCase);
        assertEquals(upperCurrency, lowerCurrency);
        assertNotEquals(IdempotencyKey.of("BUY:A"), IdempotencyKey.of("buy:a"));

        ledger.apply(credit("ACCOUNT:1", mixedCase, lowerCurrency, 7L));
        ledger.apply(credit("ACCOUNT:2", lowerCase, upperCurrency, 11L));

        assertEquals(7L, ledger.balance(mixedCase, upperCurrency).minorUnits());
        assertEquals(11L, ledger.balance(lowerCase, lowerCurrency).minorUnits());
    }

    @Test
    void usesBinaryIdentifierCollationForMysqlAndMariaDbSchemasAndMigrations() throws Exception {
        for (CurrencySqlDialect dialect : List.of(CurrencySqlDialect.MYSQL, CurrencySqlDialect.MARIADB)) {
            JdbcContractRecorder recorder = captureSchemaStatements(dialect);
            List<String> statements = recorder.statements();
            assertEquals(6, statements.size(), dialect + " must create three tables and migrate three tables");
            assertEquals(List.of(false), recorder.autoCommitChanges());
            assertEquals(1, recorder.commits());
            assertEquals(0, recorder.rollbacks());
            assertStatementAt(statements, 0, "create table if not exists ks_eco_currency_balances");
            assertStatementAt(statements, 1, "create table if not exists ks_eco_currency_operations");
            assertStatementAt(statements, 2, "create table if not exists ks_eco_currency_ledger");
            assertStatementAt(statements, 3, "alter table ks_eco_currency_balances");
            assertStatementAt(statements, 4, "alter table ks_eco_currency_operations");
            assertStatementAt(statements, 5, "alter table ks_eco_currency_ledger");

            String balances = findStatement(statements, "create table if not exists ks_eco_currency_balances");
            assertBinaryColumn(balances, "account_type", 32);
            assertBinaryColumn(balances, "account_id", 128);
            assertBinaryColumn(balances, "currency_id", 32);

            String operations = findStatement(statements, "create table if not exists ks_eco_currency_operations");
            assertBinaryColumn(operations, "operation_id", 128);

            String entries = findStatement(statements, "create table if not exists ks_eco_currency_ledger");
            assertBinaryColumn(entries, "operation_id", 128);
            assertBinaryColumn(entries, "account_type", 32);
            assertBinaryColumn(entries, "account_id", 128);
            assertBinaryColumn(entries, "currency_id", 32);

            String balanceMigration = findStatement(statements, "alter table ks_eco_currency_balances");
            assertBinaryColumn(balanceMigration, "account_type", 32);
            assertBinaryColumn(balanceMigration, "account_id", 128);
            assertBinaryColumn(balanceMigration, "currency_id", 32);

            String operationMigration = findStatement(statements, "alter table ks_eco_currency_operations");
            assertBinaryColumn(operationMigration, "operation_id", 128);

            String entryMigration = findStatement(statements, "alter table ks_eco_currency_ledger");
            assertBinaryColumn(entryMigration, "operation_id", 128);
            assertBinaryColumn(entryMigration, "account_type", 32);
            assertBinaryColumn(entryMigration, "account_id", 128);
            assertBinaryColumn(entryMigration, "currency_id", 32);
        }
    }

    @Test
    void leavesSqliteAndPostgresqlOnTheirCaseSensitiveDefaultSemantics() throws Exception {
        for (CurrencySqlDialect dialect : List.of(CurrencySqlDialect.SQLITE, CurrencySqlDialect.POSTGRESQL)) {
            JdbcContractRecorder recorder = captureSchemaStatements(dialect);
            List<String> statements = recorder.statements();
            assertEquals(3, statements.size());
            assertEquals(List.of(false), recorder.autoCommitChanges());
            assertEquals(1, recorder.commits());
            assertEquals(0, recorder.rollbacks());
            String joined = String.join("\n", statements);
            assertFalse(joined.contains("collate"));
            assertFalse(joined.contains("character set"));
            assertFalse(joined.contains("modify column"));
            assertFalse(joined.contains("alter table"));
            assertStatementAt(statements, 0, "create table if not exists ks_eco_currency_balances");
            assertStatementAt(statements, 1, "create table if not exists ks_eco_currency_operations");
            assertStatementAt(statements, 2, "create table if not exists ks_eco_currency_ledger");
        }
    }

    @Test
    void generatesDialectSpecificIdempotentInsertStatements() {
        for (CurrencySqlDialect dialect : List.of(CurrencySqlDialect.MYSQL, CurrencySqlDialect.MARIADB)) {
            assertTrue(dialect.insertOperationSql().startsWith("INSERT IGNORE"));
            assertTrue(dialect.insertBalanceSql().startsWith("INSERT IGNORE"));
            assertFalse(dialect.insertOperationSql().contains("ON CONFLICT"));
            assertFalse(dialect.insertBalanceSql().contains("ON CONFLICT"));
        }
        for (CurrencySqlDialect dialect : List.of(CurrencySqlDialect.SQLITE, CurrencySqlDialect.POSTGRESQL)) {
            assertTrue(dialect.insertOperationSql().contains("ON CONFLICT (operation_id) DO NOTHING"));
            assertTrue(dialect.insertBalanceSql()
                    .contains("ON CONFLICT (account_type, account_id, currency_id) DO NOTHING"));
            assertFalse(dialect.insertOperationSql().contains("INSERT IGNORE"));
            assertFalse(dialect.insertBalanceSql().contains("INSERT IGNORE"));
        }
    }

    private JdbcCurrencyLedger sqliteLedger(String fileName) {
        String url = "jdbc:sqlite:" + tempDirectory.resolve(fileName).toAbsolutePath();
        return new JdbcCurrencyLedger(() -> DriverManager.getConnection(url), CurrencySqlDialect.SQLITE, "test-node");
    }

    private static LedgerMutation credit(
            String operationId,
            CurrencyAccount account,
            CurrencyId currency,
            long amount
    ) {
        return new LedgerMutation(
                IdempotencyKey.of(operationId),
                "TEST_CREDIT",
                "identifier-case-contract",
                List.of(new LedgerPosting(account, new Money(currency, amount), "test credit")));
    }

    private static JdbcContractRecorder captureSchemaStatements(CurrencySqlDialect dialect) throws Exception {
        JdbcContractRecorder recorder = new JdbcContractRecorder();
        JdbcCurrencyLedger ledger = new JdbcCurrencyLedger(
                recorder::openConnection, dialect, "test-node");
        ledger.initializeSchema();
        return recorder;
    }

    private static String findStatement(List<String> statements, String prefix) {
        return statements.stream()
                .filter(statement -> statement.startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing SQL statement: " + prefix));
    }

    private static void assertBinaryColumn(String sql, String column, int length) {
        String definition = column + " varchar(" + length
                + ") character set utf8mb4 collate utf8mb4_bin not null";
        assertTrue(sql.contains(definition), () -> "Missing binary identifier column: " + definition + " in " + sql);
    }

    private static void assertStatementAt(List<String> statements, int index, String prefix) {
        assertTrue(statements.get(index).startsWith(prefix),
                () -> "Expected statement " + index + " to start with " + prefix + ": " + statements.get(index));
    }
}
