package org.kseco;

import java.util.Locale;
import java.util.UUID;

/** Defines which property owners can use the personal-wallet market settlement path. */
final class PropertySalePolicy {
    private PropertySalePolicy() {
    }

    static Decision evaluate(String ownerType, String ownerId, UUID sellerUuid) {
        return evaluate(ownerType, ownerId, sellerUuid, false);
    }

    static Decision evaluate(String ownerType, String ownerId, UUID sellerUuid, boolean enterpriseAuthorized) {
        if (ownerType == null || ownerId == null || ownerId.isBlank() || sellerUuid == null) {
            return Decision.denied(Reason.INVALID_OWNER);
        }

        String normalizedOwnerType = ownerType.trim().toUpperCase(Locale.ROOT);
        if ("ENTERPRISE".equals(normalizedOwnerType)) {
            return enterpriseAuthorized
                    ? new Decision(PayoutTarget.ENTERPRISE_ACCOUNT, Reason.ALLOWED)
                    : Decision.denied(Reason.ENTERPRISE_NOT_AUTHORIZED);
        }
        if (!"PLAYER".equals(normalizedOwnerType)) {
            return Decision.denied(Reason.UNKNOWN_OWNER_TYPE);
        }

        UUID ownerUuid;
        try {
            ownerUuid = UUID.fromString(ownerId.trim());
        } catch (IllegalArgumentException ignored) {
            return Decision.denied(Reason.INVALID_OWNER);
        }
        if (!ownerUuid.equals(sellerUuid)) {
            return Decision.denied(Reason.SELLER_NOT_OWNER);
        }
        return new Decision(PayoutTarget.PLAYER_WALLET, Reason.ALLOWED);
    }

    enum PayoutTarget {
        PLAYER_WALLET,
        ENTERPRISE_ACCOUNT,
        NONE
    }

    enum Reason {
        ALLOWED,
        SELLER_NOT_OWNER,
        ENTERPRISE_NOT_AUTHORIZED,
        UNKNOWN_OWNER_TYPE,
        INVALID_OWNER
    }

    record Decision(PayoutTarget payoutTarget, Reason reason) {
        static Decision denied(Reason reason) {
            return new Decision(PayoutTarget.NONE, reason);
        }

        boolean allowed() {
            return reason == Reason.ALLOWED && payoutTarget != PayoutTarget.NONE;
        }
    }
}
