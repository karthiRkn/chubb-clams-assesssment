package com.chubb.claims.claim;

import java.time.Instant;

/** Result of a state change that does not carry decision data (assign, review, information request, resume). */
public record WorkflowResponse(String claimId, ClaimStatus status, String assignedOfficerId, Instant updatedAt) {

    public static WorkflowResponse from(Claim c) {
        return new WorkflowResponse(c.getClaimId(), c.getStatus(), c.getAssignedOfficerId(), c.getUpdatedAt());
    }
}
