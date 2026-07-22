package org.kseco.extra.bank;

import org.kseco.database.BusinessSchemaDialect;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/** JDBC persistence for atomic account interest accrual and balance mutations. */
final class BankInterestSettlementStore {

    private static final int CASH_SCALE = 2;

    private BankInterestSettlementStore() {
    }

    static void createTables(Statement statement) throws SQLException {
        createTables(statement.getConnection());
    }

    static void createTables(Connection connection) throws SQLException {
        String text128 = BusinessSchemaDialect.varchar(128);
        String text191 = BusinessSchemaDialect.varchar(191);
        String text4096 = BusinessSchemaDialect.varchar(4096);
        try (Statement statement = connection.createStatement()) {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS ks_bank_interest_state (
                    account_id %s PRIMARY KEY,
                    bank_id %s NOT NULL,
                    balance_minor BIGINT NOT NULL,
                    accrued_through BIGINT NOT NULL,
                    open_period_start BIGINT NOT NULL,
                    period_seconds BIGINT NOT NULL,
                    balance_time_minor_seconds %s NOT NULL,
                    interest_numerator_minor_seconds %s NOT NULL,
                    rate_per_period %s NOT NULL,
                    version BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL
                )
                """.formatted(text191, text128, text4096, text4096, text128));
        statement.execute("""
                CREATE TABLE IF NOT EXISTS ks_bank_interest_postings (
                    bank_id %s NOT NULL,
                    account_id %s NOT NULL,
                    period_key %s NOT NULL,
                    period_start BIGINT NOT NULL,
                    period_end BIGINT NOT NULL,
                    average_balance_minor %s NOT NULL,
                    interest_minor BIGINT NOT NULL,
                    posted_at BIGINT NOT NULL,
                    PRIMARY KEY (bank_id, account_id, period_key)
                )
                """.formatted(text128, text191, text128, text4096));
        }
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_bank_interest_postings_account",
                "ks_bank_interest_postings", "account_id", "period_end");
    }

    static SettlementResult apply(Connection connection, String bankId, String accountId,
                                  String playerUuid, double currentRate, long currentPeriodSeconds,
                                  long now, long mutationMinor, boolean allowCreate) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        requireText(bankId, "bankId");
        requireText(accountId, "accountId");
        requireText(playerUuid, "playerUuid");
        if (!Double.isFinite(currentRate) || currentRate < 0) {
            throw new IllegalArgumentException("currentRate must be finite and non-negative");
        }
        if (currentPeriodSeconds <= 0) {
            throw new IllegalArgumentException("currentPeriodSeconds must be positive");
        }
        if (now < 0) throw new IllegalArgumentException("now must be non-negative");

        AccountRow account = loadAccount(connection, bankId, accountId);
        if (account == null) {
            if (!allowCreate || mutationMinor <= 0) return SettlementResult.notApplied();
            insertAccount(connection, bankId, accountId, playerUuid, mutationMinor, now);
            BankInterestAccrualPolicy.AccrualState initial =
                    BankInterestAccrualPolicy.initialState(mutationMinor, now, currentPeriodSeconds);
            insertState(connection, bankId, accountId, initial, currentPeriodSeconds,
                    BigDecimal.valueOf(currentRate), now);
            return new SettlementResult(true, 0, 0, mutationMinor);
        }

        long actualBalanceMinor = toMinor(account.balance());
        StateRow stored = loadState(connection, bankId, accountId);
        if (stored == null) {
            long finalBalance = addMutation(actualBalanceMinor, mutationMinor);
            if (finalBalance < 0) return SettlementResult.notApplied();
            BankInterestAccrualPolicy.AccrualState initial =
                    BankInterestAccrualPolicy.initialState(finalBalance, now, currentPeriodSeconds);
            updateAccount(connection, bankId, accountId, account.balance(), finalBalance, 0, now);
            insertState(connection, bankId, accountId, initial, currentPeriodSeconds,
                    BigDecimal.valueOf(currentRate), now);
            return new SettlementResult(true, 0, 0, finalBalance);
        }

        BankInterestAccrualPolicy.AccrualState policyState = new BankInterestAccrualPolicy.AccrualState(
                stored.balanceMinor(), stored.accruedThrough(), stored.openPeriodStart(),
                stored.balanceTimeMinorSeconds(), stored.interestNumeratorMinorSeconds());
        BankInterestAccrualPolicy.Result accrual = BankInterestAccrualPolicy.settleBeforeMutation(
                bankId, accountId, policyState, stored.ratePerPeriod(), stored.periodSeconds(), now,
                BankInterestAccrualPolicy.Mutation.none());

        long externalDelta = Math.subtractExact(actualBalanceMinor, stored.balanceMinor());
        long settledActualBalance = Math.addExact(accrual.balanceBeforeMutationMinor(), externalDelta);
        long finalBalance = addMutation(settledActualBalance, mutationMinor);
        if (finalBalance < 0) return SettlementResult.notApplied();

        for (BankInterestAccrualPolicy.PeriodAccrual posting : accrual.periodAccruals()) {
            insertPosting(connection, bankId, accountId, posting, now);
        }
        updateAccount(connection, bankId, accountId, account.balance(), finalBalance,
                accrual.totalInterestMinor(), now);

        BankInterestAccrualPolicy.AccrualState nextState = withBalance(accrual.nextState(), finalBalance);
        long nextPeriodSeconds = stored.periodSeconds();
        if (stored.periodSeconds() != currentPeriodSeconds
                && nextState.openPeriodBalanceTimeMinorSeconds().signum() == 0
                && nextState.openPeriodInterestNumeratorMinorSeconds().signum() == 0) {
            nextState = BankInterestAccrualPolicy.initialState(finalBalance, now, currentPeriodSeconds);
            nextPeriodSeconds = currentPeriodSeconds;
        }
        updateState(connection, stored, nextState, nextPeriodSeconds,
                BigDecimal.valueOf(currentRate), now);
        return new SettlementResult(true, accrual.totalInterestMinor(),
                accrual.periodAccruals().size(), finalBalance);
    }

    static long toMinor(double amount) {
        if (!Double.isFinite(amount)) throw new IllegalArgumentException("amount must be finite");
        return BigDecimal.valueOf(amount).movePointRight(CASH_SCALE)
                .setScale(0, RoundingMode.HALF_EVEN).longValueExact();
    }

    static double fromMinor(long amountMinor) {
        return BigDecimal.valueOf(amountMinor, CASH_SCALE).doubleValue();
    }

    private static AccountRow loadAccount(Connection connection, String bankId, String accountId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance FROM ks_bank_accounts WHERE id=? AND bank_id=?")) {
            statement.setString(1, accountId);
            statement.setString(2, bankId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new AccountRow(result.getDouble(1)) : null;
            }
        }
    }

    private static StateRow loadState(Connection connection, String bankId, String accountId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_minor,accrued_through,open_period_start,period_seconds,"
                        + "balance_time_minor_seconds,interest_numerator_minor_seconds,"
                        + "rate_per_period,version FROM ks_bank_interest_state "
                        + "WHERE account_id=? AND bank_id=?")) {
            statement.setString(1, accountId);
            statement.setString(2, bankId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                try {
                    return new StateRow(accountId, bankId, result.getLong("balance_minor"),
                            result.getLong("accrued_through"), result.getLong("open_period_start"),
                            result.getLong("period_seconds"),
                            new BigInteger(result.getString("balance_time_minor_seconds")),
                            new BigDecimal(result.getString("interest_numerator_minor_seconds")),
                            new BigDecimal(result.getString("rate_per_period")),
                            result.getLong("version"));
                } catch (NumberFormatException error) {
                    throw new SQLException("Invalid bank interest state for " + accountId, error);
                }
            }
        }
    }

    private static void insertAccount(Connection connection, String bankId, String accountId,
                                      String playerUuid, long balanceMinor, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ks_bank_accounts "
                        + "(id,bank_id,player_uuid,balance,interest_earned,opened_at,last_interest_at) "
                        + "VALUES (?,?,?,?,0,?,?)")) {
            statement.setString(1, accountId);
            statement.setString(2, bankId);
            statement.setString(3, playerUuid);
            statement.setDouble(4, fromMinor(balanceMinor));
            statement.setLong(5, now);
            statement.setLong(6, now);
            if (statement.executeUpdate() != 1) throw new SQLException("Account insert was not applied");
        }
    }

    private static void updateAccount(Connection connection, String bankId, String accountId,
                                      double expectedBalance, long finalBalanceMinor,
                                      long interestMinor, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ks_bank_accounts SET balance=?,"
                        + "interest_earned=COALESCE(interest_earned,0)+?,last_interest_at=? "
                        + "WHERE id=? AND bank_id=? AND balance=?")) {
            statement.setDouble(1, fromMinor(finalBalanceMinor));
            statement.setDouble(2, fromMinor(interestMinor));
            statement.setLong(3, now);
            statement.setString(4, accountId);
            statement.setString(5, bankId);
            statement.setDouble(6, expectedBalance);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Concurrent bank account mutation detected for " + accountId);
            }
        }
    }

    private static void insertState(Connection connection, String bankId, String accountId,
                                    BankInterestAccrualPolicy.AccrualState state,
                                    long periodSeconds, BigDecimal rate, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ks_bank_interest_state "
                        + "(account_id,bank_id,balance_minor,accrued_through,open_period_start,"
                        + "period_seconds,balance_time_minor_seconds,interest_numerator_minor_seconds,"
                        + "rate_per_period,version,updated_at) VALUES (?,?,?,?,?,?,?,?,?,1,?)")) {
            statement.setString(1, accountId);
            statement.setString(2, bankId);
            bindState(statement, 3, state, periodSeconds, rate);
            statement.setLong(10, now);
            if (statement.executeUpdate() != 1) throw new SQLException("Interest state insert was not applied");
        }
    }

    private static void updateState(Connection connection, StateRow stored,
                                    BankInterestAccrualPolicy.AccrualState state,
                                    long periodSeconds, BigDecimal rate, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ks_bank_interest_state SET balance_minor=?,accrued_through=?,"
                        + "open_period_start=?,period_seconds=?,balance_time_minor_seconds=?,"
                        + "interest_numerator_minor_seconds=?,rate_per_period=?,version=version+1,updated_at=? "
                        + "WHERE account_id=? AND bank_id=? AND version=? AND balance_minor=? "
                        + "AND accrued_through=?")) {
            bindState(statement, 1, state, periodSeconds, rate);
            statement.setLong(8, now);
            statement.setString(9, stored.accountId());
            statement.setString(10, stored.bankId());
            statement.setLong(11, stored.version());
            statement.setLong(12, stored.balanceMinor());
            statement.setLong(13, stored.accruedThrough());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Concurrent bank interest state mutation detected for "
                        + stored.accountId());
            }
        }
    }

    private static void bindState(PreparedStatement statement, int start,
                                  BankInterestAccrualPolicy.AccrualState state,
                                  long periodSeconds, BigDecimal rate) throws SQLException {
        statement.setLong(start, state.balanceMinor());
        statement.setLong(start + 1, state.accruedThrough());
        statement.setLong(start + 2, state.openPeriodStart());
        statement.setLong(start + 3, periodSeconds);
        statement.setString(start + 4, state.openPeriodBalanceTimeMinorSeconds().toString());
        statement.setString(start + 5,
                state.openPeriodInterestNumeratorMinorSeconds().toPlainString());
        statement.setString(start + 6, rate.stripTrailingZeros().toPlainString());
    }

    private static void insertPosting(Connection connection, String bankId, String accountId,
                                      BankInterestAccrualPolicy.PeriodAccrual posting,
                                      long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ks_bank_interest_postings "
                        + "(bank_id,account_id,period_key,period_start,period_end,"
                        + "average_balance_minor,interest_minor,posted_at) VALUES (?,?,?,?,?,?,?,?)")) {
            statement.setString(1, bankId);
            statement.setString(2, accountId);
            statement.setString(3, posting.periodKey());
            statement.setLong(4, posting.periodStart());
            statement.setLong(5, posting.periodEnd());
            statement.setString(6, posting.averageBalanceMinor().toPlainString());
            statement.setLong(7, posting.interestMinor());
            statement.setLong(8, now);
            if (statement.executeUpdate() != 1) throw new SQLException("Interest posting was not applied");
        }
    }

    private static BankInterestAccrualPolicy.AccrualState withBalance(
            BankInterestAccrualPolicy.AccrualState state, long balanceMinor) {
        return new BankInterestAccrualPolicy.AccrualState(balanceMinor, state.accruedThrough(),
                state.openPeriodStart(), state.openPeriodBalanceTimeMinorSeconds(),
                state.openPeriodInterestNumeratorMinorSeconds());
    }

    private static long addMutation(long balanceMinor, long mutationMinor) {
        return Math.addExact(balanceMinor, mutationMinor);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    record SettlementResult(boolean applied, long interestMinor, int postingCount,
                            long finalBalanceMinor) {
        static SettlementResult notApplied() {
            return new SettlementResult(false, 0, 0, 0);
        }
    }

    private record AccountRow(double balance) {
    }

    private record StateRow(String accountId, String bankId, long balanceMinor,
                            long accruedThrough, long openPeriodStart, long periodSeconds,
                            BigInteger balanceTimeMinorSeconds,
                            BigDecimal interestNumeratorMinorSeconds,
                            BigDecimal ratePerPeriod, long version) {
    }
}
