package com.chubb.claims.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record ClaimResponse(
        Long id,
        String claimId,
        String claimantName,
        String claimantEmail,
        String market,
        ClaimType claimType,
        String description,
        LocalDate incidentDate,
        BigDecimal claimedAmount,
        String currency,
        ClaimStatus status,
        String assignedOfficerId,
        String decisionReason,
        BigDecimal settledAmount,
        Instant decidedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static ClaimResponse from(Claim c) {
        return new ClaimResponse(c.getId(), c.getClaimId(), c.getClaimantName(), c.getClaimantEmail(), c.getMarket(),
                c.getClaimType(), c.getDescription(), c.getIncidentDate(), c.getClaimedAmount(), c.getCurrency(),
                c.getStatus(), c.getAssignedOfficerId(), c.getDecisionReason(), c.getSettledAmount(), c.getDecidedAt(),
                c.getCreatedAt(), c.getUpdatedAt());
    }
}
