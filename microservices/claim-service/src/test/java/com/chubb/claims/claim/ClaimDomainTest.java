package com.chubb.claims.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chubb.claims.common.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Pure unit tests (no Spring, no database) of the lifecycle rules in the Claim aggregate. */
class ClaimDomainTest {

    private Claim newClaim() {
        return new Claim("CLM-2026-000101", "mei.tan", "Mei", "m@example.com", "POL-0001", "SG", ClaimType.MOTOR, "d",
                LocalDate.of(2026, 10, 12), new BigDecimal("2400.00"), "INR");
    }

    private Claim underReview() {
        Claim c = newClaim();
        c.startReview("OFF-1", false);
        return c;
    }

    private Claim approved() {
        Claim c = underReview();
        c.decide(ClaimStatus.APPROVED, "ok", new BigDecimal("2000.00"), "OFF-1");
        return c;
    }

    @Test
    void startsSubmitted() {
        Claim c = newClaim();
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
        assertThat(c.getApprovedAmount()).isNull();
        assertThat(c.getAssignedOfficerId()).isNull();
        assertThat(c.getClaimantId()).isEqualTo("mei.tan");
    }

    @Test
    void happyPathThroughInformationRequestApprovalAndSettlement() {
        Claim c = newClaim();
        c.startReview("OFF-1", false);
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
        c.requestInformation("OFF-1");
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.MORE_INFO_REQUIRED);
        c.resumeAfterInformation();
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
        c.decide(ClaimStatus.APPROVED, "ok", new BigDecimal("2000.00"), "OFF-1");
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(c.getApprovedAmount()).isEqualByComparingTo("2000.00");
        assertThat(c.getDecidedAt()).isNotNull();
        assertThat(c.getSettledAt()).isNull();
        c.settle("OFF-1");
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.SETTLED);
        assertThat(c.getSettledAt()).isNotNull();
        assertThat(c.getSettledByOfficerId()).isEqualTo("OFF-1");
    }

    @Test
    void explicitReviewCanResumeFromMoreInfoRequiredWhenNothingIsOpen() {
        Claim c = underReview();
        c.requestInformation("OFF-1");
        c.startReview("OFF-1", false);
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
    }

    /**
     * The documented workflow, written out by hand: from each status, the moves that are allowed. Every other move must be a
     * 409. Reaching each status is done with the same calls the Assessment Service makes, so this also proves the six
     * statuses are all reachable.
     */
    @Test
    void exactlyTheDocumentedMovesAreAllowedFromEachStatus() {
        Map<ClaimStatus, Set<ClaimStatus>> allowed = new EnumMap<>(ClaimStatus.class);
        allowed.put(ClaimStatus.SUBMITTED, EnumSet.of(ClaimStatus.UNDER_REVIEW));
        allowed.put(ClaimStatus.UNDER_REVIEW, EnumSet.of(ClaimStatus.MORE_INFO_REQUIRED, ClaimStatus.APPROVED, ClaimStatus.REJECTED));
        allowed.put(ClaimStatus.MORE_INFO_REQUIRED, EnumSet.of(ClaimStatus.UNDER_REVIEW));
        allowed.put(ClaimStatus.APPROVED, EnumSet.of(ClaimStatus.SETTLED));
        allowed.put(ClaimStatus.REJECTED, EnumSet.noneOf(ClaimStatus.class));
        allowed.put(ClaimStatus.SETTLED, EnumSet.noneOf(ClaimStatus.class));

        Map<ClaimStatus, java.util.function.Supplier<Claim>> reach = new EnumMap<>(ClaimStatus.class);
        reach.put(ClaimStatus.SUBMITTED, this::newClaim);
        reach.put(ClaimStatus.UNDER_REVIEW, this::underReview);
        reach.put(ClaimStatus.MORE_INFO_REQUIRED, () -> {
            Claim c = underReview();
            c.requestInformation("OFF-1");
            return c;
        });
        reach.put(ClaimStatus.APPROVED, this::approved);
        reach.put(ClaimStatus.REJECTED, () -> {
            Claim c = underReview();
            c.decide(ClaimStatus.REJECTED, "no", null, "OFF-1");
            return c;
        });
        reach.put(ClaimStatus.SETTLED, () -> {
            Claim c = approved();
            c.settle("OFF-1");
            return c;
        });

        // the move to each target status, as the code does it
        Map<ClaimStatus, Consumer<Claim>> move = new EnumMap<>(ClaimStatus.class);
        move.put(ClaimStatus.UNDER_REVIEW, c -> {
            if (c.getStatus() == ClaimStatus.MORE_INFO_REQUIRED) {
                c.resumeAfterInformation();
            } else {
                c.startReview("OFF-1", false);
            }
        });
        move.put(ClaimStatus.MORE_INFO_REQUIRED, c -> c.requestInformation("OFF-1"));
        move.put(ClaimStatus.APPROVED, c -> c.decide(ClaimStatus.APPROVED, "ok", BigDecimal.ONE, "OFF-1"));
        move.put(ClaimStatus.REJECTED, c -> c.decide(ClaimStatus.REJECTED, "no", null, "OFF-1"));
        move.put(ClaimStatus.SETTLED, c -> c.settle("OFF-1"));

        for (ClaimStatus from : ClaimStatus.values()) {
            assertThat(reach.get(from).get().getStatus()).as("reaching " + from).isEqualTo(from);
            for (ClaimStatus to : move.keySet()) {
                Claim c = reach.get(from).get();
                if (allowed.get(from).contains(to)) {
                    move.get(to).accept(c);
                    assertThat(c.getStatus()).as(from + " -> " + to).isEqualTo(to);
                } else {
                    assertConflict(() -> move.get(to).accept(c));
                    assertThat(c.getStatus()).as(from + " -> " + to + " must change nothing").isEqualTo(from);
                }
            }
        }
    }

    @Test
    void explicitReviewIsRefusedWhileInformationRequestsAreStillOpen() {
        Claim c = underReview();
        c.requestInformation("OFF-1");
        assertConflict(() -> c.startReview("OFF-1", true));
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.MORE_INFO_REQUIRED);
    }

    @Test
    void aRejectedClaimCannotBeSettledAndSaysSo() {
        Claim c = underReview();
        c.decide(ClaimStatus.REJECTED, "no", null, "OFF-1");
        assertThatThrownBy(() -> c.settle("OFF-1")).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getMessage()).isEqualTo("A rejected claim cannot be settled");
        });
    }

    @Test
    void terminalStatesAreFinal() {
        Claim rejected = underReview();
        rejected.decide(ClaimStatus.REJECTED, "x", null, "OFF-1");
        Claim settled = approved();
        settled.settle("OFF-1");
        for (Claim c : List.of(rejected, settled)) {
            assertThat(c.getStatus().isTerminal()).isTrue();
            assertConflict(() -> c.assign("OFF-2"));
            assertConflict(() -> c.startReview("OFF-1", false));
            assertConflict(() -> c.requestInformation("OFF-1"));
            assertConflict(c::resumeAfterInformation);
            assertConflict(() -> c.decide(ClaimStatus.APPROVED, "again", BigDecimal.ONE, "OFF-1"));
            assertConflict(() -> c.settle("OFF-1"));
        }
    }

    @Test
    void approvedIsNotTerminalAndCanStillBeAssigned() {
        Claim c = approved();
        assertThat(c.getStatus().isTerminal()).isFalse();
        c.assign("OFF-2");
        assertThat(c.getAssignedOfficerId()).isEqualTo("OFF-2");
    }

    @Test
    void onlyTheStatusesTheWorkflowEndsInAreTerminal() {
        assertThat(EnumSet.allOf(ClaimStatus.class).stream().filter(ClaimStatus::isTerminal).toList())
                .containsExactlyInAnyOrder(ClaimStatus.SETTLED, ClaimStatus.REJECTED);
    }

    @Test
    void onlyTheAssignedOfficerMayRequestInformationDecideOrSettle() {
        Claim review = underReview();
        assertMismatch(() -> review.requestInformation("OFF-2"));
        assertMismatch(() -> review.decide(ClaimStatus.REJECTED, "x", null, "OFF-2"));
        assertMismatch(() -> review.decide(ClaimStatus.APPROVED, "x", BigDecimal.ONE, "OFF-2"));
        assertThat(review.getStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);

        Claim approved = approved();
        assertMismatch(() -> approved.settle("OFF-2"));
        assertThat(approved.getStatus()).isEqualTo(ClaimStatus.APPROVED);

        Claim assignedToSomeoneElse = newClaim();
        assignedToSomeoneElse.assign("OFF-1");
        assertMismatch(() -> assignedToSomeoneElse.startReview("OFF-2", false));
    }

    @Test
    void aDecisionIsApprovedOrRejectedNothingElse() {
        Claim c = underReview();
        assertThatThrownBy(() -> c.decide(ClaimStatus.SETTLED, "x", BigDecimal.ONE, "OFF-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.decide(ClaimStatus.UNDER_REVIEW, "x", null, "OFF-1")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- claimant edits ----

    @Test
    void aSubmittedClaimCanBeCorrectedAndOnlyRealChangesAreReported() {
        Claim c = newClaim();
        List<String> changes = c.updateDetails("Mei Tan Lee", null, "POL-0002", ClaimType.PROPERTY, null,
                LocalDate.of(2026, 10, 13), new BigDecimal("2500.00"));
        assertThat(changes).containsExactly("claimantName: changed", "policyNumber: POL-0001 -> POL-0002",
                "claimType: MOTOR -> PROPERTY", "incidentDate: 2026-10-12 -> 2026-10-13", "claimedAmount: 2400.00 -> 2500.00");
        assertThat(c.getClaimantName()).isEqualTo("Mei Tan Lee");
        assertThat(c.getClaimedAmount()).isEqualByComparingTo("2500.00");
        assertThat(c.getClaimantId()).as("the claimant can never be changed").isEqualTo("mei.tan");

        assertThat(c.updateDetails("Mei Tan Lee", null, "POL-0002", ClaimType.PROPERTY, null, LocalDate.of(2026, 10, 13),
                new BigDecimal("2500.0"))).as("the same values change nothing").isEmpty();
    }

    @Test
    void personalDataIsNeverCopiedIntoTheChangeSummary() {
        Claim c = newClaim();
        List<String> changes = c.updateDetails("A Different Name", "other@example.com", null, null, "A new description", null, null);
        assertThat(String.join("\n", changes)).doesNotContain("A Different Name").doesNotContain("other@example.com")
                .doesNotContain("A new description");
        assertThat(changes).containsExactly("claimantName: changed", "claimantEmail: changed", "incidentDescription: changed");
    }

    @Test
    void aClaimCanOnlyBeCorrectedWhileSubmitted() {
        for (Claim c : List.of(underReview(), approved())) {
            assertThatThrownBy(() -> c.updateDetails("New", null, null, null, null, null, null))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.getCode()).isEqualTo("CLAIM_NOT_EDITABLE");
                    });
            assertThat(c.getClaimantName()).isEqualTo("Mei");
        }
    }

    private static void assertConflict(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    private static void assertMismatch(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getCode()).isEqualTo("OFFICER_MISMATCH");
        });
    }
}
