package com.chubb.claims.claim.internal;

import com.chubb.claims.claim.Claim;
import com.chubb.claims.claim.ClaimStatus;
import com.chubb.claims.common.OfficerIds;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/** Request and response bodies of the service-to-service API ({@code /internal/v1}), used by the Assessment Service. */
public final class InternalDtos {

    private InternalDtos() {
    }

    public enum Decision {
        APPROVED(ClaimStatus.APPROVED),
        REJECTED(ClaimStatus.REJECTED);

        private final ClaimStatus status;

        Decision(ClaimStatus status) {
            this.status = status;
        }

        public ClaimStatus toStatus() {
            return status;
        }
    }

    /**
     * {@code hasOpenInformationRequests} is a fact owned by the Assessment Service (it stores the information requests); it
     * looks it up and passes it, so the Claim Service never has to call back.
     *
     * <p>{@code officerVerified} says that the caller already knows {@code officerId} is a real claims officer: the Assessment
     * Service sets it when the officer is the person whose token it checked. When it is absent or false (an administrator acting
     * for an officer) and starting the review would assign an unassigned claim to {@code officerId}, the Claim Service asks the
     * User Service whether that name is an active claims officer, as it does for every other assignment.
     */
    public record StartReviewCommand(
            @NotBlank @Size(max = 50) @Pattern(regexp = OfficerIds.NOT_PLACEHOLDER, message = OfficerIds.PLACEHOLDER_MESSAGE) String officerId,
            @NotNull Boolean hasOpenInformationRequests,
            Boolean officerVerified) {
    }

    public record RequestInformationCommand(
            @NotBlank @Size(max = 50) @Pattern(regexp = OfficerIds.NOT_PLACEHOLDER, message = OfficerIds.PLACEHOLDER_MESSAGE) String requestedByOfficerId) {
    }

    /** {@code answeredBy} is the claimant's login name, for the history; it may be left out. */
    public record ResumeAfterInformationCommand(@Size(max = 50) String answeredBy) {
    }

    /** APPROVED needs a positive {@code approvedAmount} that is not more than the claimed amount; REJECTED must carry none. */
    public record DecisionRequest(
            @NotNull Decision decision,
            @NotBlank @Size(max = 2000) String decisionReason,
            @Digits(integer = 17, fraction = 2) BigDecimal approvedAmount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter upper-case ISO currency code, e.g. INR") String currency,
            @NotBlank @Size(max = 50) @Pattern(regexp = OfficerIds.NOT_PLACEHOLDER, message = OfficerIds.PLACEHOLDER_MESSAGE) String decidedByOfficerId) {
    }

    public record DecisionResponse(String claimId, ClaimStatus status, String decisionReason, BigDecimal approvedAmount,
                                   String currency, String decidedByOfficerId, Instant decidedAt, Instant updatedAt) {
        public static DecisionResponse from(Claim c) {
            return new DecisionResponse(c.getClaimId(), c.getStatus(), c.getDecisionReason(), c.getApprovedAmount(),
                    c.getCurrency(), c.getDecidedByOfficerId(), c.getDecidedAt(), c.getUpdatedAt());
        }
    }

    public record SettlementCommand(
            @NotBlank @Size(max = 50) @Pattern(regexp = OfficerIds.NOT_PLACEHOLDER, message = OfficerIds.PLACEHOLDER_MESSAGE) String settledByOfficerId) {
    }

    public record SettlementResponse(String claimId, ClaimStatus status, BigDecimal approvedAmount, String currency,
                                     String settledByOfficerId, Instant settledAt, Instant updatedAt) {
        public static SettlementResponse from(Claim c) {
            return new SettlementResponse(c.getClaimId(), c.getStatus(), c.getApprovedAmount(), c.getCurrency(),
                    c.getSettledByOfficerId(), c.getSettledAt(), c.getUpdatedAt());
        }
    }

    /**
     * Minimal view for a service that only needs to know where a claim is in its lifecycle, who submitted it and who has it
     * (no claimant name, e-mail or description). {@code claimantId} lets the Assessment Service apply the same "a claimant sees
     * only their own claims" rule, and {@code assignedOfficerId} the same "an officer sees only the queue and their own claims"
     * rule; the first is null for claims that predate the column, the second for an unassigned claim.
     */
    public record ClaimStatusView(String claimId, ClaimStatus status, String claimantId, String assignedOfficerId) {
    }
}
