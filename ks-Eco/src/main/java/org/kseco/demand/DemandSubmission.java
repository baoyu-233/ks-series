package org.kseco.demand;

import java.util.Objects;
import java.util.UUID;

/** Immutable input prepared after the caller has standardized an item. */
public record DemandSubmission(
        DemandOperationId operationId,
        String campaignId,
        UUID playerId,
        StandardItemSignature itemSignature,
        long quantity,
        long unitPriceMinor,
        String currencyId
) {
    public DemandSubmission {
        Objects.requireNonNull(operationId, "operationId");
        campaignId = DemandValidation.requireIdentifier(campaignId, "campaignId", 128);
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(itemSignature, "itemSignature");
        if (quantity <= 0L) throw new IllegalArgumentException("quantity must be positive and finite");
        if (unitPriceMinor <= 0L) throw new IllegalArgumentException("unitPriceMinor must be positive and finite");
        Objects.requireNonNull(currencyId, "currencyId");
        if (currencyId.isEmpty() || currencyId.length() > 32) {
            throw new IllegalArgumentException("currencyId must contain 1-32 characters");
        }
    }
}
