package org.kseco.extra.bank;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/** Pure balance-time interest accrual with deterministic period records. */
final class BankInterestAccrualPolicy {

    private static final BigDecimal ZERO_DECIMAL = BigDecimal.ZERO;
    private static final BigInteger ZERO_INTEGER = BigInteger.ZERO;

    private BankInterestAccrualPolicy() {
    }

    static AccrualState initialState(long balanceMinor, long at, long periodSeconds) {
        requireNonNegative(balanceMinor, "balanceMinor");
        requireNonNegative(at, "at");
        requirePositive(periodSeconds, "periodSeconds");
        return new AccrualState(balanceMinor, at, periodStart(at, periodSeconds),
                ZERO_INTEGER, ZERO_DECIMAL);
    }

    /**
     * Accrues the old balance through {@code now}, then applies the mutation.
     * Rate changes must first call this method with {@link Mutation#none()} so
     * the open-period weighted accumulator retains the earlier rate slice.
     */
    static Result settleBeforeMutation(String bankId, String accountId, AccrualState state,
                                       BigDecimal ratePerPeriod, long periodSeconds, long now,
                                       Mutation mutation) {
        requireText(bankId, "bankId");
        requireText(accountId, "accountId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(ratePerPeriod, "ratePerPeriod");
        Objects.requireNonNull(mutation, "mutation");
        requirePositive(periodSeconds, "periodSeconds");
        requireNonNegative(now, "now");
        if (ratePerPeriod.signum() < 0) {
            throw new IllegalArgumentException("ratePerPeriod must be non-negative");
        }
        validateState(state, periodSeconds, now);

        long cursor = state.accruedThrough();
        long currentPeriodStart = state.openPeriodStart();
        long workingBalance = state.balanceMinor();
        long totalInterest = 0;
        BigInteger balanceTime = state.openPeriodBalanceTimeMinorSeconds();
        BigDecimal interestNumerator = state.openPeriodInterestNumeratorMinorSeconds();
        List<PeriodAccrual> periods = new ArrayList<>();

        while (cursor < now) {
            long periodEnd = Math.addExact(currentPeriodStart, periodSeconds);
            long sliceEnd = Math.min(now, periodEnd);
            long elapsed = sliceEnd - cursor;
            if (elapsed > 0) {
                BigInteger elapsedValue = BigInteger.valueOf(elapsed);
                balanceTime = balanceTime.add(BigInteger.valueOf(workingBalance).multiply(elapsedValue));
                interestNumerator = interestNumerator.add(BigDecimal.valueOf(workingBalance)
                        .multiply(ratePerPeriod)
                        .multiply(BigDecimal.valueOf(elapsed)));
            }
            cursor = sliceEnd;

            if (cursor == periodEnd) {
                long interestMinor = interestNumerator
                        .divide(BigDecimal.valueOf(periodSeconds), 0, RoundingMode.HALF_EVEN)
                        .longValueExact();
                BigDecimal averageBalanceMinor = new BigDecimal(balanceTime)
                        .divide(BigDecimal.valueOf(periodSeconds), 8, RoundingMode.HALF_EVEN)
                        .stripTrailingZeros();
                periods.add(new PeriodAccrual(
                        periodKey(bankId, accountId, currentPeriodStart, periodSeconds),
                        currentPeriodStart,
                        periodEnd,
                        balanceTime,
                        averageBalanceMinor,
                        interestMinor));
                workingBalance = Math.addExact(workingBalance, interestMinor);
                totalInterest = Math.addExact(totalInterest, interestMinor);
                currentPeriodStart = periodEnd;
                balanceTime = ZERO_INTEGER;
                interestNumerator = ZERO_DECIMAL;
            }
        }

        long balanceBeforeMutation = workingBalance;
        long balanceAfterMutation = applyMutation(balanceBeforeMutation, mutation);
        AccrualState nextState = new AccrualState(balanceAfterMutation, now, currentPeriodStart,
                balanceTime, interestNumerator.stripTrailingZeros());
        return new Result(state.balanceMinor(), balanceBeforeMutation, balanceAfterMutation,
                totalInterest, List.copyOf(periods), nextState);
    }

    static String periodKey(String bankId, String accountId, long periodStart, long periodSeconds) {
        requireText(bankId, "bankId");
        requireText(accountId, "accountId");
        requireNonNegative(periodStart, "periodStart");
        requirePositive(periodSeconds, "periodSeconds");
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "bank-interest:"
                + encoder.encodeToString(bankId.getBytes(StandardCharsets.UTF_8)) + ":"
                + encoder.encodeToString(accountId.getBytes(StandardCharsets.UTF_8)) + ":"
                + periodStart + ":" + periodSeconds;
    }

    private static long applyMutation(long balanceMinor, Mutation mutation) {
        return switch (mutation.type()) {
            case NONE -> balanceMinor;
            case DEPOSIT -> Math.addExact(balanceMinor, mutation.amountMinor());
            case WITHDRAW -> {
                if (mutation.amountMinor() > balanceMinor) {
                    throw new IllegalArgumentException("withdrawal exceeds settled balance");
                }
                yield balanceMinor - mutation.amountMinor();
            }
        };
    }

    private static void validateState(AccrualState state, long periodSeconds, long now) {
        requireNonNegative(state.balanceMinor(), "state.balanceMinor");
        requireNonNegative(state.accruedThrough(), "state.accruedThrough");
        requireNonNegative(state.openPeriodStart(), "state.openPeriodStart");
        Objects.requireNonNull(state.openPeriodBalanceTimeMinorSeconds(),
                "state.openPeriodBalanceTimeMinorSeconds");
        Objects.requireNonNull(state.openPeriodInterestNumeratorMinorSeconds(),
                "state.openPeriodInterestNumeratorMinorSeconds");
        if (state.openPeriodBalanceTimeMinorSeconds().signum() < 0
                || state.openPeriodInterestNumeratorMinorSeconds().signum() < 0) {
            throw new IllegalArgumentException("open-period accumulators must be non-negative");
        }
        if (state.accruedThrough() > now) {
            throw new IllegalArgumentException("now precedes accruedThrough");
        }
        long expectedPeriodStart = periodStart(state.accruedThrough(), periodSeconds);
        if (state.openPeriodStart() != expectedPeriodStart) {
            throw new IllegalArgumentException("openPeriodStart does not match accruedThrough");
        }
        if (state.accruedThrough() == expectedPeriodStart
                && (state.openPeriodBalanceTimeMinorSeconds().signum() != 0
                || state.openPeriodInterestNumeratorMinorSeconds().signum() != 0)) {
            throw new IllegalArgumentException("period-boundary state cannot carry open accumulators");
        }
    }

    private static long periodStart(long timestamp, long periodSeconds) {
        return Math.floorDiv(timestamp, periodSeconds) * periodSeconds;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must be non-negative");
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    enum MutationType {
        NONE,
        DEPOSIT,
        WITHDRAW
    }

    record Mutation(MutationType type, long amountMinor) {
        Mutation {
            Objects.requireNonNull(type, "type");
            if (amountMinor < 0) throw new IllegalArgumentException("amountMinor must be non-negative");
            if (type == MutationType.NONE && amountMinor != 0) {
                throw new IllegalArgumentException("NONE mutation must have zero amount");
            }
            if (type != MutationType.NONE && amountMinor == 0) {
                throw new IllegalArgumentException("balance mutation must have a positive amount");
            }
        }

        static Mutation none() {
            return new Mutation(MutationType.NONE, 0);
        }

        static Mutation deposit(long amountMinor) {
            return new Mutation(MutationType.DEPOSIT, amountMinor);
        }

        static Mutation withdraw(long amountMinor) {
            return new Mutation(MutationType.WITHDRAW, amountMinor);
        }
    }

    record AccrualState(long balanceMinor, long accruedThrough, long openPeriodStart,
                        BigInteger openPeriodBalanceTimeMinorSeconds,
                        BigDecimal openPeriodInterestNumeratorMinorSeconds) {
    }

    record PeriodAccrual(String periodKey, long periodStart, long periodEnd,
                         BigInteger balanceTimeMinorSeconds, BigDecimal averageBalanceMinor,
                         long interestMinor) {
    }

    record Result(long openingBalanceMinor, long balanceBeforeMutationMinor,
                  long balanceAfterMutationMinor, long totalInterestMinor,
                  List<PeriodAccrual> periodAccruals, AccrualState nextState) {
    }
}
