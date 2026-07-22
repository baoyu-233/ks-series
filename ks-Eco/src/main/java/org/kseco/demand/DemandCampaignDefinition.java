package org.kseco.demand;

import java.util.Objects;

/** Finite campaign definition. Zero never means unlimited. */
public record DemandCampaignDefinition(
        String campaignId,
        String regionId,
        StandardItemSignature itemSignature,
        long targetQuantity,
        long budgetMinor,
        long unitPriceMinor,
        long perPlayerLimit,
        String currencyId,
        DemandCampaignStatus initialStatus,
        long startsAtEpochMillis,
        long endsAtEpochMillis
) {
    public static final String SUPPORTED_CURRENCY_ID = "CASH";

    public DemandCampaignDefinition {
        campaignId = DemandValidation.requireIdentifier(campaignId, "campaignId", 128);
        regionId = DemandValidation.requireIdentifier(regionId, "regionId", 128);
        Objects.requireNonNull(itemSignature, "itemSignature");
        if (targetQuantity <= 0L) throw new IllegalArgumentException("targetQuantity must be positive and finite");
        if (budgetMinor <= 0L) throw new IllegalArgumentException("budgetMinor must be positive and finite");
        if (unitPriceMinor <= 0L) throw new IllegalArgumentException("unitPriceMinor must be positive and finite");
        if (perPlayerLimit <= 0L || perPlayerLimit > targetQuantity) {
            throw new IllegalArgumentException("perPlayerLimit must be between 1 and targetQuantity");
        }
        if (budgetMinor < unitPriceMinor) {
            throw new IllegalArgumentException("budgetMinor must fund at least one item");
        }
        if (!SUPPORTED_CURRENCY_ID.equals(currencyId)) {
            throw new IllegalArgumentException("demand campaigns support CASH only");
        }
        Objects.requireNonNull(initialStatus, "initialStatus");
        if (initialStatus != DemandCampaignStatus.DRAFT && initialStatus != DemandCampaignStatus.ACTIVE) {
            throw new IllegalArgumentException("initialStatus must be DRAFT or ACTIVE");
        }
        if (startsAtEpochMillis < 0L || endsAtEpochMillis <= startsAtEpochMillis) {
            throw new IllegalArgumentException("campaign must have a finite positive time window");
        }
    }
}
