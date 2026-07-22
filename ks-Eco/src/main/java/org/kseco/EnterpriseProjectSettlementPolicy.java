package org.kseco;

/** Fail-closed boundary for project flows that would otherwise mix SQL and an external wallet call. */
final class EnterpriseProjectSettlementPolicy {
    private EnterpriseProjectSettlementPolicy() { }

    static boolean requiresExternalWallet(String bidderType, double prepayment, double depositRatio) {
        return "PLAYER".equalsIgnoreCase(bidderType) && (prepayment > 0.0d || depositRatio > 0.0d);
    }
}
