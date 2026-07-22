package org.kseco;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PropertySalePolicyTest {
    @Test
    void playerOwnerUsesDurablePersonalWalletSettlement() {
        UUID seller = UUID.randomUUID();

        PropertySalePolicy.Decision decision = PropertySalePolicy.evaluate(
                "PLAYER", seller.toString(), seller);

        assertTrue(decision.allowed());
        assertEquals(PropertySalePolicy.Reason.ALLOWED, decision.reason());
        assertEquals(PropertySalePolicy.PayoutTarget.PLAYER_WALLET, decision.payoutTarget());
    }

    @Test
    void differentPlayerCannotSellTheProperty() {
        PropertySalePolicy.Decision decision = PropertySalePolicy.evaluate(
                "PLAYER", UUID.randomUUID().toString(), UUID.randomUUID());

        assertFalse(decision.allowed());
        assertEquals(PropertySalePolicy.Reason.SELLER_NOT_OWNER, decision.reason());
        assertEquals(PropertySalePolicy.PayoutTarget.NONE, decision.payoutTarget());
    }

    @Test
    void enterpriseOwnershipRequiresExplicitAuthority() {
        UUID ordinaryMember = UUID.randomUUID();
        UUID enterpriseOwner = UUID.randomUUID();

        PropertySalePolicy.Decision ordinaryDecision = PropertySalePolicy.evaluate(
                "ENTERPRISE", "enterprise-1", ordinaryMember);
        PropertySalePolicy.Decision ownerDecision = PropertySalePolicy.evaluate(
                "ENTERPRISE", "enterprise-1", enterpriseOwner, true);

        assertFalse(ordinaryDecision.allowed());
        assertTrue(ownerDecision.allowed());
        assertEquals(PropertySalePolicy.Reason.ENTERPRISE_NOT_AUTHORIZED, ordinaryDecision.reason());
        assertEquals(PropertySalePolicy.PayoutTarget.ENTERPRISE_ACCOUNT, ownerDecision.payoutTarget());
    }

    @Test
    void enterprisePropertyNeverResolvesToPersonalWalletPayout() {
        PropertySalePolicy.Decision decision = PropertySalePolicy.evaluate(
                "ENTERPRISE", "enterprise-1", UUID.randomUUID(), true);

        assertEquals(PropertySalePolicy.PayoutTarget.ENTERPRISE_ACCOUNT, decision.payoutTarget());
    }

    @Test
    void unknownOrMalformedOwnershipFailsClosed() {
        UUID seller = UUID.randomUUID();

        assertFalse(PropertySalePolicy.evaluate("TRUST", seller.toString(), seller).allowed());
        assertFalse(PropertySalePolicy.evaluate("PLAYER", "not-a-uuid", seller).allowed());
        assertFalse(PropertySalePolicy.evaluate(null, seller.toString(), seller).allowed());
    }
}
