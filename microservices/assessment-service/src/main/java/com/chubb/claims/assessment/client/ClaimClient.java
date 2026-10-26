package com.chubb.claims.assessment.client;

import com.chubb.claims.assessment.AssessmentDtos.DecisionResponse;
import com.chubb.claims.assessment.AssessmentDtos.SettlementResponse;
import com.chubb.claims.assessment.AssessmentDtos.WorkflowResponse;
import com.chubb.claims.common.DownstreamClient;
import java.math.BigDecimal;

/**
 * What the Assessment Service needs from the Claim Service: where a claim is and who owns it, and every lifecycle
 * transition. The Claim Service enforces every lifecycle rule; errors it reports (404 {@code CLAIM_NOT_FOUND}, 409
 * {@code INVALID_CLAIM_TRANSITION} / {@code OFFICER_MISMATCH}, 400 {@code CURRENCY_MISMATCH} / {@code INVALID_APPROVED_AMOUNT})
 * are raised here as ApiExceptions with the same code. Only the fields used here are declared.
 */
public class ClaimClient {

    /**
     * Lifecycle position, submitter and assigned officer, without any claimant data. {@code status} is kept as text so a new
     * status in the Claim Service cannot break parsing. {@code claimantId} is null for claims that predate it and
     * {@code assignedOfficerId} for an unassigned claim.
     */
    public record ClaimStatusView(String claimId, String status, String claimantId, String assignedOfficerId) {
    }

    private final DownstreamClient http;

    public ClaimClient(DownstreamClient http) {
        this.http = http;
    }

    /** 404 {@code CLAIM_NOT_FOUND} (raised as an ApiException) when the claim does not exist. */
    public ClaimStatusView status(String claimId) {
        return http.get(ClaimStatusView.class, "/internal/v1/claims/{claimId}/status", claimId);
    }

    /**
     * @param officerVerified true when {@code officerId} is the officer whose own token this service just checked; false when
     *                        an administrator names the officer, in which case the Claim Service checks that the name is an
     *                        active claims officer before it assigns an unassigned claim to it
     */
    public WorkflowResponse startReview(String claimId, String officerId, boolean hasOpenInformationRequests, boolean officerVerified) {
        return http.post(WorkflowResponse.class, new StartReview(officerId, hasOpenInformationRequests, officerVerified),
                "/internal/v1/claims/{claimId}/review", claimId);
    }

    /** UNDER_REVIEW to MORE_INFO_REQUIRED, by the assigned officer. 404 for an unknown claim, 409 for any other state or officer. */
    public void requestInformation(String claimId, String requestedByOfficerId) {
        http.post(Void.class, new RequestInformation(requestedByOfficerId), "/internal/v1/claims/{claimId}/request-information", claimId);
    }

    /** MORE_INFO_REQUIRED to UNDER_REVIEW. 409 when the claim is not waiting for information. */
    public void resumeAfterInformation(String claimId, String answeredBy) {
        http.post(Void.class, new ResumeAfterInformation(answeredBy), "/internal/v1/claims/{claimId}/resume-after-information", claimId);
    }

    public DecisionResponse decide(String claimId, String decision, String decisionReason, BigDecimal approvedAmount, String currency,
                                   String decidedByOfficerId) {
        return http.post(DecisionResponse.class, new Decide(decision, decisionReason, approvedAmount, currency, decidedByOfficerId),
                "/internal/v1/claims/{claimId}/decision", claimId);
    }

    public SettlementResponse settle(String claimId, String settledByOfficerId) {
        return http.post(SettlementResponse.class, new Settle(settledByOfficerId), "/internal/v1/claims/{claimId}/settlement", claimId);
    }

    private record StartReview(String officerId, boolean hasOpenInformationRequests, boolean officerVerified) {
    }

    private record RequestInformation(String requestedByOfficerId) {
    }

    private record ResumeAfterInformation(String answeredBy) {
    }

    private record Decide(String decision, String decisionReason, BigDecimal approvedAmount, String currency, String decidedByOfficerId) {
    }

    private record Settle(String settledByOfficerId) {
    }
}
