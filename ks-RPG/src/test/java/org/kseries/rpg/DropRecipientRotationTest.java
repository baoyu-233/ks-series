package org.kseries.rpg;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DropRecipientRotationTest {
    private final UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID third = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void rotatesAcrossEligibleParticipantsAndWraps() {
        DropRecipientRotation rotation = new DropRecipientRotation();
        List<UUID> participants = List.of(first, second, third);

        assertEquals(first, rotation.next("elite", participants).orElseThrow());
        assertEquals(second, rotation.next("elite", participants).orElseThrow());
        assertEquals(third, rotation.next("elite", participants).orElseThrow());
        assertEquals(first, rotation.next("elite", participants).orElseThrow());
    }

    @Test
    void keepsIndependentCursorPerDropTable() {
        DropRecipientRotation rotation = new DropRecipientRotation();
        List<UUID> participants = List.of(first, second);

        assertEquals(first, rotation.next("common", participants).orElseThrow());
        assertEquals(second, rotation.next("common", participants).orElseThrow());
        assertEquals(first, rotation.next("rare", participants).orElseThrow());
    }

    @Test
    void removesDuplicatesWithoutChangingFirstParticipationOrder() {
        DropRecipientRotation rotation = new DropRecipientRotation();
        List<UUID> participants = java.util.Arrays.asList(first, first, null, second);

        assertEquals(first, rotation.next("elite", participants).orElseThrow());
        assertEquals(second, rotation.next("elite", participants).orElseThrow());
    }

    @Test
    void emptyCandidateListDoesNotAdvanceCursor() {
        DropRecipientRotation rotation = new DropRecipientRotation();

        assertTrue(rotation.next("elite", List.of()).isEmpty());
        assertEquals(first, rotation.next("elite", List.of(first, second)).orElseThrow());
    }
}
