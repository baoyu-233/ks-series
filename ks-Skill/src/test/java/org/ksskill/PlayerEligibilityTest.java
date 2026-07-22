package org.ksskill;

import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.entity.bot.Bot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerEligibilityTest {

    @Test
    void detectsLeavesServerBotClassName() {
        assertTrue(PlayerEligibility.isLeavesBotType(org.leavesmc.leaves.bot.ServerBot.class));
    }

    @Test
    void detectsInheritedLeavesBotInterface() {
        assertTrue(PlayerEligibility.isLeavesBotType(IndirectLeavesBot.class));
    }

    @Test
    void acceptsOrdinaryTypes() {
        assertFalse(PlayerEligibility.isLeavesBotType(OrdinaryPlayerType.class));
    }

    private interface DerivedBot extends Bot {
    }

    private static final class IndirectLeavesBot implements DerivedBot {
    }

    private static final class OrdinaryPlayerType {
    }
}
