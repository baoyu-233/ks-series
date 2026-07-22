package org.kseco.demand;

public enum DemandCampaignStatus {
    DRAFT,
    ACTIVE,
    PAUSED,
    COMPLETED,
    EXPIRED,
    CANCELLED;

    boolean canTransitionTo(DemandCampaignStatus next) {
        if (next == null || next == this) return false;
        return switch (this) {
            case DRAFT -> next == ACTIVE || next == CANCELLED || next == EXPIRED;
            case ACTIVE -> next == PAUSED || next == COMPLETED || next == EXPIRED || next == CANCELLED;
            case PAUSED -> next == ACTIVE || next == EXPIRED || next == CANCELLED;
            case COMPLETED, EXPIRED, CANCELLED -> false;
        };
    }
}
