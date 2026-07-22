package org.kseco.extra.politic;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

final class PoliticWebResultTest {

    @Test
    void voteResponsePropagatesAutomaticAdvanceFailure() {
        VoteManager.Tally tally = new VoteManager.Tally(
                "SENATE_VOTING", 3, 2, 2, 0, 0,
                true, true, false, "2 赞成");
        ProposalManager.TransitionResult failure = ProposalManager.TransitionResult.fail(
                "enactment journal unavailable");

        PoliticWebHandler.WebActionResult response = PoliticWebHandler.voteResponse(
                "vote-1", tally, "SENATE_VOTING", failure);

        assertEquals(409, response.statusCode());
        assertEquals(false, response.body().get("success"));
        assertEquals("enactment journal unavailable", response.body().get("error"));
    }

    @Test
    void approvedReviewReturnsEnactmentFailure() {
        ProposalManager.TransitionResult approval = ProposalManager.TransitionResult.success(
                null, "TRIBUNE_REVIEW", "APPROVED");
        ProposalManager.TransitionResult enactmentFailure = ProposalManager.TransitionResult.fail(
                "tax schema unavailable");

        ProposalManager.TransitionResult result = PoliticWebHandler.continueApprovedEnactment(
                "APPROVED", approval, () -> enactmentFailure);

        assertSame(enactmentFailure, result);
    }

    @Test
    void failedReviewDoesNotAttemptEnactment() {
        ProposalManager.TransitionResult failure = ProposalManager.TransitionResult.fail("transition failed");
        AtomicBoolean called = new AtomicBoolean();

        ProposalManager.TransitionResult result = PoliticWebHandler.continueApprovedEnactment(
                "APPROVED", failure, () -> {
                    called.set(true);
                    return ProposalManager.TransitionResult.success(null, "APPROVED", "ENACTED");
                });

        assertSame(failure, result);
        assertFalse(called.get());
    }
}
