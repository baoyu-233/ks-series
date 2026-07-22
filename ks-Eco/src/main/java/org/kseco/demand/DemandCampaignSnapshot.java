package org.kseco.demand;

public record DemandCampaignSnapshot(
        String campaignId,
        String regionId,
        StandardItemSignature itemSignature,
        long targetQuantity,
        long remainingQuantity,
        long budgetMinor,
        long remainingBudgetMinor,
        long unitPriceMinor,
        long perPlayerLimit,
        String currencyId,
        DemandCampaignStatus status,
        long version,
        long startsAtEpochMillis,
        long endsAtEpochMillis,
        long createdAtEpochMillis,
        long updatedAtEpochMillis
) {}
