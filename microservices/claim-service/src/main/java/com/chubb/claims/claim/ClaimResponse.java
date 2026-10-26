package com.chubb.claims.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The claim as the people who may see it get it. {@code approvedAmount} is null until the claim is approved (and stays null
 * for a rejected one); {@code settledAt} is null until it is settled. Assessment notes are never part of this.
 */
public record ClaimResponse(
        Long id,
        String claimId,
        String claimantId,
        String claimantName,
        String claimantEmail,
        String policyNumber,
        String market,
        ClaimType claimType,
        String incidentDescription,
        LocalDate incidentDate,
        BigDecimal claimedAmount,
        BigDecimal approvedAmount,
        String currency,
        ClaimStatus status,
        String assignedOfficerId,
        String decisionReason,
        Instant decidedAt,
        Instant settledAt,
        Instant createdAt,
        Instant updatedAt) {

    public static ClaimResponse from(Claim c) {
        return new ClaimResponse(c.getId(), c.getClaimId(), c.getClaimantId(), c.getClaimantName(), c.getClaimantEmail(),
                c.getPolicyNumber(), c.getMarket(), c.getClaimType(), c.getIncidentDescription(), c.getIncidentDate(),
                c.getClaimedAmount(), c.getApprovedAmount(), c.getCurrency(), c.getStatus(), c.getAssignedOfficerId(),
                c.getDecisionReason(), c.getDecidedAt(), c.getSettledAt(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
