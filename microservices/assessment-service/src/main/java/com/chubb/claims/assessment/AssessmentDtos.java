package com.chubb.claims.assessment;

import com.chubb.claims.common.OfficerIds;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Request and response bodies of the assessment routes. Wherever a request has an officer ID ({@code officerId},
 * {@code decidedByOfficerId}, {@code settledByOfficerId}), a claims officer may leave it out (it is their own login name,
 * from the token) or repeat their own name; naming anybody else is 403. Only an administrator, acting for an officer,
 * has to name one.
 */
public final class AssessmentDtos {

    private AssessmentDtos() {
    }

    public enum Decision {
        APPROVED,
        REJECTED
    }

    /** Review request. {@code assessmentNotes} is optional; blank means no note is recorded. */
    public record ReviewRequest(
            @Size(max = 50) @Pattern(regexp = OfficerIds.NOT_PLACEHOLDER, message = OfficerIds.PLACEHOLDER_MESSAGE) String officerId,
            @Size(max = 2000) String assessmentNotes) {
    }

    public record AssessmentResponse(String claimId, String officerId, AssessmentKind kind, String notes, Instant createdAt) {
        public static AssessmentResponse from(ClaimAssessment a) {
            return new AssessmentResponse(a.getClaimId(), a.getOfficerId(), a.getKind(), a.getNotes(), a.getCreatedAt());
        }
    }

    /**
     * An approval needs a positive {@code approvedAmount} that is not more than the claimed amount; a rejection must carry
     * none. The Claim Service owns those amount and currency rules and enforces them; they are checked here as well.
     */
    public record DecisionRequest(
            @NotNull Decision decision,
            @NotBlank @Size(max = 2000) String decisionReason,
            @Digits(integer = 17, fraction = 2) BigDecimal approvedAmount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter upper-case ISO currency code, e.g. INR") String currency,
            @Size(max = 50) @Pattern(regexp = OfficerIds.NOT_PLACEHOLDER, message = OfficerIds.PLACEHOLDER_MESSAGE) String decidedByOfficerId) {
    }

    /** Settles an approved claim. Both fields are optional; {@code settlementNote} is kept in the assessment history. */
    public record SettlementRequest(
            @Size(max = 50) @Pattern(regexp = OfficerIds.NOT_PLACEHOLDER, message = OfficerIds.PLACEHOLDER_MESSAGE) String settledByOfficerId,
            @Size(max = 2000) String settlementNote) {
    }

    /** Same JSON as the Claim Service's review response; {@code status} is text so a new status cannot break parsing. */
    public record WorkflowResponse(String claimId, String status, String assignedOfficerId, Instant updatedAt) {
    }

    /** Same JSON as the Claim Service's decision response. */
    public record DecisionResponse(String claimId, String status, String decisionReason, BigDecimal approvedAmount,
                                   String currency, String decidedByOfficerId, Instant decidedAt, Instant updatedAt) {
    }

    /** Same JSON as the Claim Service's settlement response. */
    public record SettlementResponse(String claimId, String status, BigDecimal approvedAmount, String currency,
                                     String settledByOfficerId, Instant settledAt, Instant updatedAt) {
    }
}
