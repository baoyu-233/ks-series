package org.kseco.extra.bank;

final class LoanPayoutState {
    static final String PENDING = "PENDING_PAYOUT";
    static final String SETTLING = "PAYOUT_SETTLING";
    static final String ACTIVE = "ACTIVE";
    static final String RECONCILE_REQUIRED = "RECONCILE_REQUIRED";

    private LoanPayoutState() {
    }

    static String startupRecovery(String status) {
        return PENDING.equals(status) || SETTLING.equals(status) ? RECONCILE_REQUIRED : status;
    }
}
