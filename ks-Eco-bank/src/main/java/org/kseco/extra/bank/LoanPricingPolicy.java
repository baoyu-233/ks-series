package org.kseco.extra.bank;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure credit scoring and fixed-term loan pricing. */
final class LoanPricingPolicy {

    private static final int MIN_SCORE = 300;
    private static final int MAX_SCORE = 850;

    private LoanPricingPolicy() {
    }

    static Profile buildProfile(Stats stats, Config config, double policyMaxPrincipal,
                                int policyMinTermDays, int policyMaxTermDays) {
        int score = config.baseScore();
        List<Factor> factors = new ArrayList<>();

        int paidBonus = Math.min(5, Math.max(0, stats.paidLoans())) * 18;
        if (paidBonus > 0) {
            score += paidBonus;
            factors.add(new Factor("PAID_HISTORY", paidBonus));
        }

        long historyDays = stats.oldestLoanAt() <= 0
                ? 0 : Math.max(0, (stats.now() - stats.oldestLoanAt()) / 86_400L);
        int historyBonus = historyDays >= 180 ? 20 : historyDays >= 90 ? 10 : 0;
        if (historyBonus > 0) {
            score += historyBonus;
            factors.add(new Factor("HISTORY_LENGTH", historyBonus));
        }

        int activePenalty = Math.min(5, Math.max(0, stats.activeLoans())) * 8;
        if (activePenalty > 0) {
            score -= activePenalty;
            factors.add(new Factor("ACTIVE_LOANS", -activePenalty));
        }

        int overduePenalty = Math.min(2, Math.max(0, stats.overdueLoans())) * 160;
        if (overduePenalty > 0) {
            score -= overduePenalty;
            factors.add(new Factor("CURRENT_OVERDUE", -overduePenalty));
        }

        int historicOverdue = Math.max(0, stats.everOverdueLoans() - stats.overdueLoans());
        int historicPenalty = Math.min(3, historicOverdue) * 60;
        if (historicPenalty > 0) {
            score -= historicPenalty;
            factors.add(new Factor("PAST_OVERDUE", -historicPenalty));
        }

        int recentPenalty = Math.max(0, Math.min(8, stats.recentRequests() - 2)) * 12;
        if (recentPenalty > 0) {
            score -= recentPenalty;
            factors.add(new Factor("RECENT_APPLICATIONS", -recentPenalty));
        }

        double utilization = policyMaxPrincipal <= 0 ? 0 : stats.outstandingPrincipal() / policyMaxPrincipal;
        int utilizationPenalty = (int) Math.round(Math.min(1.5, Math.max(0, utilization)) * 60);
        if (utilizationPenalty > 0) {
            score -= utilizationPenalty;
            factors.add(new Factor("CREDIT_UTILIZATION", -utilizationPenalty));
        }

        score = Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));
        Tier tier = tierFor(score, config);
        double limitFactor = switch (tier) {
            case A -> config.limitFactorA();
            case B -> config.limitFactorB();
            case C -> config.limitFactorC();
            case D -> config.limitFactorD();
            case E -> config.limitFactorE();
        };
        int tierTerm = switch (tier) {
            case A -> config.maxTermA();
            case B -> config.maxTermB();
            case C -> config.maxTermC();
            case D -> config.maxTermD();
            case E -> config.maxTermE();
        };
        double maxPrincipal = Math.max(0, policyMaxPrincipal * clamp(limitFactor, 0, 1));
        double availableCredit = Math.max(0, maxPrincipal - Math.max(0, stats.outstandingPrincipal()));
        int maxTermDays = Math.max(policyMinTermDays, Math.min(policyMaxTermDays, tierTerm));

        List<String> nextSteps = new ArrayList<>();
        if (stats.overdueLoans() > 0) nextSteps.add("CLEAR_OVERDUE");
        if (utilization >= 0.5) nextSteps.add("REDUCE_BALANCE");
        if (stats.recentRequests() > 2) nextSteps.add("PAUSE_APPLICATIONS");
        if (stats.paidLoans() < 3) nextSteps.add("BUILD_REPAYMENT_HISTORY");
        if (nextSteps.isEmpty()) nextSteps.add("KEEP_GOOD_STANDING");

        return new Profile(score, tier, maxPrincipal, availableCredit, maxTermDays,
                stats.paidLoans(), stats.activeLoans(), stats.overdueLoans(), stats.recentRequests(),
                Math.max(0, stats.outstandingPrincipal()), List.copyOf(factors), List.copyOf(nextSteps));
    }

    static Quote quote(Profile profile, Config config, double baseRate, double principal,
                       int termDays, long now) {
        double riskSpread = switch (profile.tier()) {
            case A -> config.riskSpreadA();
            case B -> config.riskSpreadB();
            case C -> config.riskSpreadC();
            case D -> config.riskSpreadD();
            case E -> config.riskSpreadE();
        };
        double termSpread = Math.max(0, termDays - 30) / 30.0 * config.termSpreadPer30Days();
        termSpread = Math.min(config.maxTermSpread(), termSpread);
        double effectiveRate = clamp(baseRate + riskSpread + termSpread,
                config.minEffectiveRate(), config.maxEffectiveRate());
        double totalDue = principal * (1 + effectiveRate);
        long dueAt = now + Math.max(0, termDays) * 86_400L;
        return new Quote(profile, baseRate, riskSpread, termSpread, effectiveRate, totalDue, dueAt);
    }

    private static Tier tierFor(int score, Config config) {
        if (score >= config.tierAMin()) return Tier.A;
        if (score >= config.tierBMin()) return Tier.B;
        if (score >= config.tierCMin()) return Tier.C;
        if (score >= config.tierDMin()) return Tier.D;
        return Tier.E;
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    enum Tier {
        A, B, C, D, E;

        String label() {
            return switch (this) {
                case A -> "A - \u4F18\u79C0";
                case B -> "B - \u826F\u597D";
                case C -> "C - \u7A33\u5B9A";
                case D -> "D - \u8C28\u614E";
                case E -> "E - \u53D7\u9650";
            };
        }
    }

    record Factor(String code, int points) {
    }

    record Stats(int paidLoans, int activeLoans, int overdueLoans, int everOverdueLoans,
                 int recentRequests, double outstandingPrincipal, long oldestLoanAt, long now) {
    }

    record Profile(int score, Tier tier, double maxPrincipal, double availableCredit, int maxTermDays,
                   int paidLoans, int activeLoans, int overdueLoans, int recentRequests,
                   double outstandingPrincipal, List<Factor> factors, List<String> nextSteps) {
        boolean eligible(Config config) {
            return score >= config.minEligibleScore() && overdueLoans == 0;
        }

        String summary() {
            return String.format(Locale.ROOT, "%d / %s", score, tier.label());
        }
    }

    record Quote(Profile profile, double baseRate, double riskSpread, double termSpread,
                 double effectiveRate, double totalDue, long dueAt) {
    }

    record Config(int baseScore, int minEligibleScore, int recentWindowDays,
                  int tierAMin, int tierBMin, int tierCMin, int tierDMin,
                  double limitFactorA, double limitFactorB, double limitFactorC,
                  double limitFactorD, double limitFactorE,
                  int maxTermA, int maxTermB, int maxTermC, int maxTermD, int maxTermE,
                  double riskSpreadA, double riskSpreadB, double riskSpreadC,
                  double riskSpreadD, double riskSpreadE,
                  double termSpreadPer30Days, double maxTermSpread,
                  double minEffectiveRate, double maxEffectiveRate, int quoteValidSeconds) {
    }
}
