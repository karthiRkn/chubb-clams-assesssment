package com.chubb.claims;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chubb.claims.claim.Claim;
import com.chubb.claims.claim.ClaimStatus;
import com.chubb.claims.claim.ClaimType;
import com.chubb.claims.common.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Pure unit tests (no Spring, no database) of the lifecycle rules in the Claim aggregate. */
class ClaimDomainTest {

    private Claim newClaim() {
        return new Claim("CLM-2026-000101", "Mei", "m@example.com", "SG", ClaimType.MOTOR, "d",
                LocalDate.of(2026, 10, 12), new BigDecimal("2400.00"), "INR");
    }

    private Claim underReview() {
        Claim c = newClaim();
        c.startReview("OFF-1", false);
        return c;
    }

    @Test
    void startsSubmitted() {
        assertThat(newClaim().getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
    }

    @Test
    void happyPathThroughInformationRequestToSettlement() {
        Claim c = newClaim();
        c.startReview("OFF-1", false);
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
        c.requestInformation();
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.AWAITING_INFORMATION);
        c.resumeAfterInformation();
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
        c.decide(ClaimStatus.SETTLED, "ok", new BigDecimal("2000.00"), "OFF-1");
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.SETTLED);
        assertThat(c.getDecidedAt()).isNotNull();
    }

    @Test
    void explicitReviewCanResumeFromAwaitingInformationWhenNothingIsOpen() {
        Claim c = underReview();
        c.requestInformation();
        c.startReview("OFF-1", false);
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
    }

    @Test
    void illegalTransitionsAreConflicts() {
        Claim submitted = newClaim();
        assertConflict(submitted::requestInformation);
        assertConflict(submitted::resumeAfterInformation);
        assertConflict(() -> submitted.decide(ClaimStatus.SETTLED, "x", BigDecimal.ONE, "OFF-1"));

        Claim review = underReview();
        assertConflict(() -> review.startReview("OFF-1", false));
        assertConflict(review::resumeAfterInformation);

        Claim awaiting = underReview();
        awaiting.requestInformation();
        assertConflict(awaiting::requestInformation);
        assertConflict(() -> awaiting.decide(ClaimStatus.REJECTED, "x", null, "OFF-1"));
        assertConflict(() -> awaiting.startReview("OFF-1", true));
    }

    @Test
    void terminalStatesAreFinal() {
        for (ClaimStatus outcome : new ClaimStatus[] {ClaimStatus.SETTLED, ClaimStatus.REJECTED}) {
            Claim c = underReview();
            c.decide(outcome, "x", outcome == ClaimStatus.SETTLED ? BigDecimal.ONE : null, "OFF-1");
            assertThat(c.getStatus().isTerminal()).isTrue();
            assertConflict(() -> c.assign("OFF-2"));
            assertConflict(() -> c.startReview("OFF-1", false));
            assertConflict(c::requestInformation);
            assertConflict(c::resumeAfterInformation);
            assertConflict(() -> c.decide(ClaimStatus.SETTLED, "again", BigDecimal.ONE, "OFF-1"));
        }
    }

    @Test
    void nonTerminalStatusesAreNotTerminal() {
        assertThat(ClaimStatus.SUBMITTED.isTerminal()).isFalse();
        assertThat(ClaimStatus.UNDER_REVIEW.isTerminal()).isFalse();
        assertThat(ClaimStatus.AWAITING_INFORMATION.isTerminal()).isFalse();
    }

    private static void assertConflict(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }
}
