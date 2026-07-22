package org.kseco.currency;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/** Database-neutral JDBC ledger for SQLite, MySQL/MariaDB and PostgreSQL. */
public final class JdbcCurrencyLedger implements CurrencyLedger {
    private static final int MAX_WRITE_ATTEMPTS = 6;

    private final CurrencyConnectionSupplier connections;
    private final CurrencySqlDialect dialect;
    private final String serverId;
    private final Clock clock;

    public JdbcCurrencyLedger(CurrencyConnectionSupplier connections, CurrencySqlDialect dialect, String serverId) {
        this(connections, dialect, serverId, Clock.systemUTC());
    }

    JdbcCurrencyLedger(
            CurrencyConnectionSupplier connections,
            CurrencySqlDialect dialect,
            String serverId,
            Clock clock
    ) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.serverId = requireServerId(serverId);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void initializeSchema() throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                for (String schemaStatement : schemaStatements()) {
                    statement.executeUpdate(schemaStatement);
                }
                for (String migrationStatement : identifierCollationMigrations()) {
                    statement.executeUpdate(migrationStatement);
                }
                connection.commit();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    private String[] schemaStatements() {
        String identifier32 = identifierColumn(32);
        String identifier128 = identifierColumn(128);
        return new String[]{
                """
                        CREATE TABLE IF NOT EXISTS ks_eco_currency_balances (
                            account_type %s NOT NULL,
                            account_id %s NOT NULL,
                            currency_id %s NOT NULL,
                            balance_minor BIGINT NOT NULL,
                            balance_version BIGINT NOT NULL,
                            updated_at BIGINT NOT NULL,
                            PRIMARY KEY (account_type, account_id, currency_id)
                        )
                        """.formatted(identifier32, identifier128, identifier32),
                """
                        CREATE TABLE IF NOT EXISTS ks_eco_currency_operations (
                            operation_id %s NOT NULL PRIMARY KEY,
                            request_hash VARCHAR(64) NOT NULL,
                            operation_type VARCHAR(48) NOT NULL,
                            reference_id VARCHAR(255) NOT NULL,
                            status VARCHAR(16) NOT NULL,
                            result_code VARCHAR(64) NOT NULL,
                            server_id VARCHAR(64) NOT NULL,
                            created_at BIGINT NOT NULL,
                            updated_at BIGINT NOT NULL
                        )
                        """.formatted(identifier128),
                """
                        CREATE TABLE IF NOT EXISTS ks_eco_currency_ledger (
                            entry_id VARCHAR(64) NOT NULL PRIMARY KEY,
                            operation_id %s NOT NULL,
                            sequence_no INTEGER NOT NULL,
                            account_type %s NOT NULL,
                            account_id %s NOT NULL,
                            currency_id %s NOT NULL,
                            delta_minor BIGINT NOT NULL,
                            balance_after_minor BIGINT NOT NULL,
                            memo VARCHAR(255) NOT NULL,
                            server_id VARCHAR(64) NOT NULL,
                            created_at BIGINT NOT NULL,
                            UNIQUE (operation_id, sequence_no)
                        )
                        """.formatted(identifier128, identifier32, identifier128, identifier32)
        };
    }

    private String[] identifierCollationMigrations() {
        if (dialect != CurrencySqlDialect.MYSQL && dialect != CurrencySqlDialect.MARIADB) {
            return new String[0];
        }
        String identifier32 = identifierColumn(32);
        String identifier128 = identifierColumn(128);
        return new String[]{
                "ALTER TABLE ks_eco_currency_balances "
                        + "MODIFY COLUMN account_type " + identifier32 + " NOT NULL, "
                        + "MODIFY COLUMN account_id " + identifier128 + " NOT NULL, "
                        + "MODIFY COLUMN currency_id " + identifier32 + " NOT NULL",
                "ALTER TABLE ks_eco_currency_operations "
                        + "MODIFY COLUMN operation_id " + identifier128 + " NOT NULL",
                "ALTER TABLE ks_eco_currency_ledger "
                        + "MODIFY COLUMN operation_id " + identifier128 + " NOT NULL, "
                        + "MODIFY COLUMN account_type " + identifier32 + " NOT NULL, "
                        + "MODIFY COLUMN account_id " + identifier128 + " NOT NULL, "
                        + "MODIFY COLUMN currency_id " + identifier32 + " NOT NULL"
        };
    }

    private String identifierColumn(int length) {
        String type = "VARCHAR(" + length + ')';
        return switch (dialect) {
            case MYSQL, MARIADB -> type + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin";
            case SQLITE, POSTGRESQL -> type;
        };
    }

    @Override
    public Money balance(CurrencyAccount account, CurrencyId currency) throws SQLException {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(currency, "currency");
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_minor FROM ks_eco_currency_balances "
                        + "WHERE account_type=? AND account_id=? AND currency_id=?")) {
            bindKey(statement, 1, new BalanceKey(account, currency));
            try (ResultSet result = statement.executeQuery()) {
                return new Money(currency, result.next() ? result.getLong(1) : 0L);
            }
        }
    }

    @Override
    public LedgerResult apply(LedgerMutation mutation) throws SQLException {
        Objects.requireNonNull(mutation, "mutation");
        String requestHash = requestHash(mutation);
        SQLException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            try {
                return applyOnce(mutation, requestHash);
            } catch (RetryableWriteConflict conflict) {
                // Retry the complete transaction with a fresh connection and snapshot.
            } catch (SQLException exception) {
                if (!isRetryable(exception) || attempt == MAX_WRITE_ATTEMPTS) throw exception;
                lastFailure = exception;
            }
            LockSupport.parkNanos(attempt * 2_000_000L);
        }
        throw lastFailure != null ? lastFailure : new SQLException("Currency ledger write conflict");
    }

    private LedgerResult applyOnce(LedgerMutation mutation, String requestHash) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                long now = clock.millis();
                if (!claimOperation(connection, mutation, requestHash, now)) {
                    connection.rollback();
                    return loadExistingResult(mutation.operationId(), requestHash);
                }

                Map<BalanceKey, BalanceState> initial = new LinkedHashMap<>();
                for (LedgerPosting posting : mutation.postings()) {
                    BalanceKey key = posting.balanceKey();
                    if (!initial.containsKey(key)) {
                        ensureBalanceRow(connection, key, now);
                        initial.put(key, readBalanceState(connection, key));
                    }
                }

                Map<BalanceKey, Long> running = new LinkedHashMap<>();
                initial.forEach((key, state) -> running.put(key, state.balance()));
                for (LedgerPosting posting : mutation.postings()) {
                    BalanceKey key = posting.balanceKey();
                    long next;
                    try {
                        next = Math.addExact(running.get(key), posting.delta().minorUnits());
                    } catch (ArithmeticException exception) {
                        throw new SQLException("Currency balance overflow for " + key, exception);
                    }
                    if (next < 0L) {
                        finishOperation(connection, mutation.operationId(), "REJECTED",
                                LedgerResult.CODE_INSUFFICIENT_FUNDS, now);
                        connection.commit();
                        return LedgerResult.rejected(LedgerResult.CODE_INSUFFICIENT_FUNDS, false);
                    }
                    running.put(key, next);
                }

                for (Map.Entry<BalanceKey, BalanceState> entry : initial.entrySet()) {
                    BalanceKey key = entry.getKey();
                    BalanceState state = entry.getValue();
                    long next = running.get(key);
                    if (next != state.balance() && !updateBalance(connection, key, state, next, now)) {
                        throw new RetryableWriteConflict();
                    }
                }

                Map<BalanceKey, Long> entryBalances = new LinkedHashMap<>();
                initial.forEach((key, state) -> entryBalances.put(key, state.balance()));
                int sequence = 0;
                for (LedgerPosting posting : mutation.postings()) {
                    BalanceKey key = posting.balanceKey();
                    long after = Math.addExact(entryBalances.get(key), posting.delta().minorUnits());
                    insertEntry(connection, mutation.operationId(), sequence++, posting, after, now);
                    entryBalances.put(key, after);
                }
                finishOperation(connection, mutation.operationId(), "APPLIED", LedgerResult.CODE_APPLIED, now);
                connection.commit();
                return LedgerResult.applied(false, toMoneyMap(running));
            } catch (RetryableWriteConflict conflict) {
                connection.rollback();
                throw conflict;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    private boolean claimOperation(
            Connection connection,
            LedgerMutation mutation,
            String requestHash,
            long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(dialect.insertOperationSql())) {
            statement.setString(1, mutation.operationId().value());
            statement.setString(2, requestHash);
            statement.setString(3, mutation.operationType());
            statement.setString(4, mutation.reference());
            statement.setString(5, "PENDING");
            statement.setString(6, "PENDING");
            statement.setString(7, serverId);
            statement.setLong(8, now);
            statement.setLong(9, now);
            return statement.executeUpdate() == 1;
        }
    }

    private void ensureBalanceRow(Connection connection, BalanceKey key, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(dialect.insertBalanceSql())) {
            bindKey(statement, 1, key);
            statement.setLong(4, 0L);
            statement.setLong(5, 0L);
            statement.setLong(6, now);
            statement.executeUpdate();
        }
    }

    private BalanceState readBalanceState(Connection connection, BalanceKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_minor, balance_version FROM ks_eco_currency_balances "
                        + "WHERE account_type=? AND account_id=? AND currency_id=?")) {
            bindKey(statement, 1, key);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Currency balance row was not created: " + key);
                return new BalanceState(result.getLong(1), result.getLong(2));
            }
        }
    }

    private boolean updateBalance(
            Connection connection,
            BalanceKey key,
            BalanceState current,
            long next,
            long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ks_eco_currency_balances SET balance_minor=?, balance_version=?, updated_at=? "
                        + "WHERE account_type=? AND account_id=? AND currency_id=? "
                        + "AND balance_minor=? AND balance_version=?")) {
            statement.setLong(1, next);
            statement.setLong(2, Math.incrementExact(current.version()));
            statement.setLong(3, now);
            bindKey(statement, 4, key);
            statement.setLong(7, current.balance());
            statement.setLong(8, current.version());
            return statement.executeUpdate() == 1;
        }
    }

    private void insertEntry(
            Connection connection,
            IdempotencyKey operationId,
            int sequence,
            LedgerPosting posting,
            long balanceAfter,
            long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ks_eco_currency_ledger "
                        + "(entry_id, operation_id, sequence_no, account_type, account_id, currency_id, "
                        + "delta_minor, balance_after_minor, memo, server_id, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, digest(operationId.value() + ':' + sequence));
            statement.setString(2, operationId.value());
            statement.setInt(3, sequence);
            statement.setString(4, posting.account().type());
            statement.setString(5, posting.account().id());
            statement.setString(6, posting.delta().currency().value());
            statement.setLong(7, posting.delta().minorUnits());
            statement.setLong(8, balanceAfter);
            statement.setString(9, posting.memo());
            statement.setString(10, serverId);
            statement.setLong(11, now);
            statement.executeUpdate();
        }
    }

    private void finishOperation(
            Connection connection,
            IdempotencyKey operationId,
            String status,
            String resultCode,
            long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ks_eco_currency_operations SET status=?, result_code=?, updated_at=? WHERE operation_id=?")) {
            statement.setString(1, status);
            statement.setString(2, resultCode);
            statement.setLong(3, now);
            statement.setString(4, operationId.value());
            if (statement.executeUpdate() != 1) throw new SQLException("Currency operation disappeared");
        }
    }

    private LedgerResult loadExistingResult(IdempotencyKey operationId, String requestHash) throws SQLException {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT request_hash, status, result_code FROM ks_eco_currency_operations WHERE operation_id=?")) {
            statement.setString(1, operationId.value());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Existing currency operation was not visible");
                if (!requestHash.equals(result.getString(1))) return LedgerResult.idempotencyConflict();
                String status = result.getString(2);
                String code = result.getString(3);
                if ("REJECTED".equals(status)) return LedgerResult.rejected(code, true);
                if (!"APPLIED".equals(status)) throw new SQLException("Currency operation is incomplete: " + status);
            }
            return LedgerResult.applied(true, loadOperationBalances(connection, operationId));
        }
    }

    private Map<BalanceKey, Money> loadOperationBalances(Connection connection, IdempotencyKey operationId)
            throws SQLException {
        Map<BalanceKey, Money> balances = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT account_type, account_id, currency_id, balance_after_minor "
                        + "FROM ks_eco_currency_ledger WHERE operation_id=? ORDER BY sequence_no")) {
            statement.setString(1, operationId.value());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    CurrencyId currency = CurrencyId.of(result.getString(3));
                    BalanceKey key = new BalanceKey(
                            new CurrencyAccount(result.getString(1), result.getString(2)), currency);
                    balances.put(key, new Money(currency, result.getLong(4)));
                }
            }
        }
        return balances;
    }

    private Connection openConnection() throws SQLException {
        Connection connection = connections.openConnection();
        if (connection == null) throw new SQLException("Currency connection supplier returned null");
        return connection;
    }

    private static void bindKey(PreparedStatement statement, int firstIndex, BalanceKey key) throws SQLException {
        statement.setString(firstIndex, key.account().type());
        statement.setString(firstIndex + 1, key.account().id());
        statement.setString(firstIndex + 2, key.currency().value());
    }

    private static Map<BalanceKey, Money> toMoneyMap(Map<BalanceKey, Long> balances) {
        Map<BalanceKey, Money> result = new LinkedHashMap<>();
        balances.forEach((key, amount) -> result.put(key, new Money(key.currency(), amount)));
        return result;
    }

    private static String requestHash(LedgerMutation mutation) {
        MessageDigest digest = sha256();
        update(digest, mutation.operationType());
        update(digest, mutation.reference());
        for (LedgerPosting posting : mutation.postings()) {
            update(digest, posting.account().type());
            update(digest, posting.account().id());
            update(digest, posting.delta().currency().value());
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(posting.delta().minorUnits()).array());
            update(digest, posting.memo());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String digest(String value) {
        return HexFormat.of().formatHex(sha256().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static boolean isRetryable(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && (state.equals("40001") || state.equals("40P01"))) return true;
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("database is locked") || lower.contains("database is busy")) return true;
            }
        }
        return false;
    }

    private static String requireServerId(String value) {
        Objects.requireNonNull(value, "serverId");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64) {
            throw new IllegalArgumentException("serverId must contain 1-64 characters");
        }
        return trimmed;
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record BalanceState(long balance, long version) {}

    private static final class RetryableWriteConflict extends RuntimeException {}
}
