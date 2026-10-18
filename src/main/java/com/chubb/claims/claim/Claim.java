package com.chubb.claims.claim;

import com.chubb.claims.common.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Claim aggregate. {@code id} is the internal key; {@code claimId} is the stable public business reference.
 * All lifecycle transitions live here so they are enforced regardless of the caller.
 */
@Entity
@Table(name = "claims")
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false, unique = true, updatable = false, length = 32)
    private String claimId;

    @Column(name = "claimant_name", nullable = false, length = 200)
    private String claimantName;

    @Column(name = "claimant_email", nullable = false, length = 254)
    private String claimantEmail;

    @Column(nullable = false, length = 2)
    private String market;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_type", nullable = false, length = 20)
    private ClaimType claimType;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(name = "incident_date", nullable = false)
    private LocalDate incidentDate;

    @Column(name = "claimed_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal claimedAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ClaimStatus status;

    @Column(name = "assigned_officer_id", length = 50)
    private String assignedOfficerId;

    @Column(name = "decision_reason", length = 2000)
    private String decisionReason;

    @Column(name = "settled_amount", precision = 19, scale = 2)
    private BigDecimal settledAmount;

    @Column(name = "decided_by_officer_id", length = 50)
    private String decidedByOfficerId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Claim() {
    }

    public Claim(String claimId, String claimantName, String claimantEmail, String market, ClaimType claimType,
                 String description, LocalDate incidentDate, BigDecimal claimedAmount, String currency) {
        this.claimId = claimId;
        this.claimantName = claimantName;
        this.claimantEmail = claimantEmail;
        this.market = market;
        this.claimType = claimType;
        this.description = description;
        this.incidentDate = incidentDate;
        this.claimedAmount = claimedAmount;
        this.currency = currency;
        this.status = ClaimStatus.SUBMITTED;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // ---- lifecycle ----

    public void assign(String officerId) {
        requireNotTerminal("assign an officer to");
        this.assignedOfficerId = officerId;
    }

    /** SUBMITTED -> UNDER_REVIEW, or AWAITING_INFORMATION -> UNDER_REVIEW (explicit resume). */
    public void startReview(String officerId, boolean hasOpenInformationRequests) {
        if (status != ClaimStatus.SUBMITTED && status != ClaimStatus.AWAITING_INFORMATION) {
            throw invalid("Review can only be started for a SUBMITTED or AWAITING_INFORMATION claim, but claim is " + status);
        }
        if (status == ClaimStatus.AWAITING_INFORMATION && hasOpenInformationRequests) {
            throw invalid("Review cannot resume while information requests are still OPEN");
        }
        if (assignedOfficerId != null && !assignedOfficerId.equals(officerId)) {
            throw ApiException.conflict("OFFICER_MISMATCH",
                    "Claim is assigned to " + assignedOfficerId + ", not " + officerId);
        }
        this.assignedOfficerId = officerId;
        this.status = ClaimStatus.UNDER_REVIEW;
    }

    /** UNDER_REVIEW -> AWAITING_INFORMATION. */
    public void requestInformation() {
        if (status != ClaimStatus.UNDER_REVIEW) {
            throw invalid("Information can only be requested for a claim under review, but claim is " + status);
        }
        this.status = ClaimStatus.AWAITING_INFORMATION;
    }

    /** AWAITING_INFORMATION -> UNDER_REVIEW once every request has been answered. */
    public void resumeAfterInformation() {
        if (status != ClaimStatus.AWAITING_INFORMATION) {
            throw invalid("Information can only be supplied for a claim awaiting information, but claim is " + status);
        }
        this.status = ClaimStatus.UNDER_REVIEW;
    }

    /** Guard used before accepting an answer, so a wrong-state claim fails before anything is changed. */
    public void requireAwaitingInformation() {
        if (status != ClaimStatus.AWAITING_INFORMATION) {
            throw invalid("Information can only be supplied for a claim awaiting information, but claim is " + status);
        }
    }

    /** UNDER_REVIEW -> SETTLED / REJECTED (terminal). */
    public void decide(ClaimStatus outcome, String reason, BigDecimal settledAmount, String officerId) {
        requireUnderReview();
        if (!outcome.isTerminal()) {
            throw new IllegalArgumentException("Decision outcome must be terminal");
        }
        this.status = outcome;
        this.decisionReason = reason;
        this.settledAmount = settledAmount;
        this.decidedByOfficerId = officerId;
        this.decidedAt = Instant.now();
    }

    /** Checked first by the decision workflow so a duplicate decision is a 409 even if the body is also wrong. */
    public void requireUnderReview() {
        if (status != ClaimStatus.UNDER_REVIEW) {
            throw invalid("A decision can only be recorded for a claim under review, but claim is " + status);
        }
    }

    private void requireNotTerminal(String action) {
        if (status.isTerminal()) {
            throw invalid("Cannot " + action + " a claim that is already " + status);
        }
    }

    private static ApiException invalid(String message) {
        return ApiException.conflict("INVALID_CLAIM_TRANSITION", message);
    }

    // ---- getters ----

    public Long getId() { return id; }
    public String getClaimId() { return claimId; }
    public String getClaimantName() { return claimantName; }
    public String getClaimantEmail() { return claimantEmail; }
    public String getMarket() { return market; }
    public ClaimType getClaimType() { return claimType; }
    public String getDescription() { return description; }
    public LocalDate getIncidentDate() { return incidentDate; }
    public BigDecimal getClaimedAmount() { return claimedAmount; }
    public String getCurrency() { return currency; }
    public ClaimStatus getStatus() { return status; }
    public String getAssignedOfficerId() { return assignedOfficerId; }
    public String getDecisionReason() { return decisionReason; }
    public BigDecimal getSettledAmount() { return settledAmount; }
    public String getDecidedByOfficerId() { return decidedByOfficerId; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
