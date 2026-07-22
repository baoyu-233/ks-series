package org.kseco.extra.bank;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BankInterestAccrualPolicyTest {

    private static final long PERIOD = 100;
    private static final BigDecimal TEN_PERCENT = new BigDecimal("0.10");

    @Test
    void instantDepositOnlyEarnsForItsActualSlice() {
        BankInterestAccrualPolicy.AccrualState initial =
                BankInterestAccrualPolicy.initialState(10_000, 1_000, PERIOD);

        BankInterestAccrualPolicy.Result deposit = BankInterestAccrualPolicy.settleBeforeMutation(
                "BANK-1", "ACCOUNT-1", initial, TEN_PERCENT, PERIOD, 1_099,
                BankInterestAccrualPolicy.Mutation.deposit(90_000));
        BankInterestAccrualPolicy.Result settlement = BankInterestAccrualPolicy.settleBeforeMutation(
                "BANK-1", "ACCOUNT-1", deposit.nextState(), TEN_PERCENT, PERIOD, 1_100,
                BankInterestAccrualPolicy.Mutation.none());

        assertEquals(0, deposit.totalInterestMinor());
        assertEquals(1_090, settlement.totalInterestMinor());
        assertEquals(0, new BigDecimal("10900")
                .compareTo(settlement.periodAccruals().getFirst().averageBalanceMinor()));
        assertEquals(101_090, settlement.balanceAfterMutationMinor());
    }

    @Test
    void withdrawalUsesBalanceAfterCompletedInterest() {
        BankInterestAccrualPolicy.AccrualState initial =
                BankInterestAccrualPolicy.initialState(10_000, 2_000, PERIOD);

        BankInterestAccrualPolicy.Result result = BankInterestAccrualPolicy.settleBeforeMutation(
                "BANK-1", "ACCOUNT-1", initial, TEN_PERCENT, PERIOD, 2_100,
                BankInterestAccrualPolicy.Mutation.withdraw(5_000));

        assertEquals(1_000, result.totalInterestMinor());
        assertEquals(11_000, result.balanceBeforeMutationMinor());
        assertEquals(6_000, result.balanceAfterMutationMinor());
        assertEquals(6_000, result.nextState().balanceMinor());
    }

    @Test
    void downtimeIsSlicedIntoIdempotentCompoundingPeriods() {
        BankInterestAccrualPolicy.AccrualState initial =
                BankInterestAccrualPolicy.initialState(10_000, 3_000, PERIOD);

        BankInterestAccrualPolicy.Result result = BankInterestAccrualPolicy.settleBeforeMutation(
                "BANK-1", "ACCOUNT-1", initial, TEN_PERCENT, PERIOD, 3_250,
                BankInterestAccrualPolicy.Mutation.none());

        assertEquals(2, result.periodAccruals().size());
        assertEquals(1_000, result.periodAccruals().get(0).interestMinor());
        assertEquals(1_100, result.periodAccruals().get(1).interestMinor());
        assertEquals(2_100, result.totalInterestMinor());
        assertEquals(12_100, result.balanceAfterMutationMinor());
        assertEquals(605_000, result.nextState().openPeriodBalanceTimeMinorSeconds().longValueExact());
        assertNotEquals(result.periodAccruals().get(0).periodKey(),
                result.periodAccruals().get(1).periodKey());
    }

    @Test
    void rateChangeKeepsTheEarlierWeightedSlice() {
        BankInterestAccrualPolicy.AccrualState initial =
                BankInterestAccrualPolicy.initialState(10_000, 4_000, PERIOD);
        BankInterestAccrualPolicy.Result firstHalf = BankInterestAccrualPolicy.settleBeforeMutation(
                "BANK-1", "ACCOUNT-1", initial, TEN_PERCENT, PERIOD, 4_050,
                BankInterestAccrualPolicy.Mutation.none());

        BankInterestAccrualPolicy.Result secondHalf = BankInterestAccrualPolicy.settleBeforeMutation(
                "BANK-1", "ACCOUNT-1", firstHalf.nextState(), new BigDecimal("0.20"), PERIOD, 4_100,
                BankInterestAccrualPolicy.Mutation.none());

        assertEquals(1_500, secondHalf.totalInterestMinor());
        assertEquals(0, new BigDecimal("10000")
                .compareTo(secondHalf.periodAccruals().getFirst().averageBalanceMinor()));
    }

    @Test
    void periodKeyIsDeterministicAndScoped() {
        String first = BankInterestAccrualPolicy.periodKey("BANK-1", "ACCOUNT-1", 5_000, PERIOD);

        assertEquals(first, BankInterestAccrualPolicy.periodKey("BANK-1", "ACCOUNT-1", 5_000, PERIOD));
        assertNotEquals(first, BankInterestAccrualPolicy.periodKey("BANK-1", "ACCOUNT-2", 5_000, PERIOD));
        assertNotEquals(first, BankInterestAccrualPolicy.periodKey("BANK-1", "ACCOUNT-1", 5_100, PERIOD));
    }

    @Test
    void withdrawalCannotExceedSettledBalance() {
        BankInterestAccrualPolicy.AccrualState initial =
                BankInterestAccrualPolicy.initialState(1_000, 6_000, PERIOD);

        assertThrows(IllegalArgumentException.class, () ->
                BankInterestAccrualPolicy.settleBeforeMutation(
                        "BANK-1", "ACCOUNT-1", initial, BigDecimal.ZERO, PERIOD, 6_000,
                        BankInterestAccrualPolicy.Mutation.withdraw(1_001)));
    }
}
