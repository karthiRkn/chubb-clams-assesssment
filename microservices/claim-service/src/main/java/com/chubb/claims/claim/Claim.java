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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Claim aggregate. {@code id} is the internal key; {@code claimId} is the stable public business reference.
 * All lifecycle transitions live here so they are enforced regardless of the caller (see {@link ClaimStatus} for the
 * allowed moves).
 */
@Entity
@Table(name = "claims")
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false, unique = true, updatable = false, length = 32)
    private String claimId;

    /** Login name (token subject) of the claimant who submitted the claim; null for claims that predate it. Never changes. */
    @Column(name = "claimant_id", length = 100, updatable = false)
    private String claimantId;

    @Column(name = "claimant_name", nullable = false, length = 200)
    private String claimantName;

    @Column(name = "claimant_email", nullable = false, length = 254)
    private String claimantEmail;

    /** Null for claims created before the field existed; the API requires it for every new claim. */
    @Column(name = "policy_number", length = 50)
    private String policyNumber;

    @Column(nullable = false, length = 2)
    private String market;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_type", nullable = false, length = 20)
    private ClaimType claimType;

    @Column(name = "incident_description", nullable = false, length = 2000)
    private String incidentDescription;

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

    /** Why the claim was approved or rejected. */
    @Column(name = "decision_reason", length = 2000)
    private String decisionReason;

    /** Set when the claim is approved; null until then and for a rejected claim. */
    @Column(name = "approved_amount", precision = 19, scale = 2)
    private BigDecimal approvedAmount;

    @Column(name = "decided_by_officer_id", length = 50)
    private String decidedByOfficerId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "settled_by_officer_id", length = 50)
    private String settledByOfficerId;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Claim() {
    }

    public Claim(String claimId, String claimantId, String claimantName, String claimantEmail, String policyNumber,
                 String market, ClaimType claimType, String incidentDescription, LocalDate incidentDate,
                 BigDecimal claimedAmount, String currency) {
        this.claimId = claimId;
        this.claimantId = claimantId;
        this.claimantName = claimantName;
        this.claimantEmail = claimantEmail;
        this.policyNumber = policyNumber;
        this.market = market;
        this.claimType = claimType;
        this.incidentDescription = incidentDescription;
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

    /** True when {@code username} is the claimant who submitted this claim; false for a claim with no recorded owner. */
    public boolean isOwnedBy(String username) {
        return claimantId != null && claimantId.equals(username);
    }

    // ---- lifecycle ----

    public void assign(String officerId) {
        requireNotTerminal("assign an officer to");
        this.assignedOfficerId = officerId;
    }

    /** SUBMITTED -> UNDER_REVIEW, or MORE_INFO_REQUIRED -> UNDER_REVIEW (explicit resume). */
    public void startReview(String officerId, boolean hasOpenInformationRequests) {
        if (status != ClaimStatus.SUBMITTED && status != ClaimStatus.MORE_INFO_REQUIRED) {
            throw invalid("Review can only be started for a SUBMITTED or MORE_INFO_REQUIRED claim, but claim is " + status);
        }
        if (status == ClaimStatus.MORE_INFO_REQUIRED && hasOpenInformationRequests) {
            throw invalid("Review cannot resume while information requests are still OPEN");
        }
        requireAssignedTo(officerId);
        this.assignedOfficerId = officerId;
        this.status = ClaimStatus.UNDER_REVIEW;
    }

    /** UNDER_REVIEW -> MORE_INFO_REQUIRED. Only the officer the claim is assigned to may ask. */
    public void requestInformation(String officerId) {
        if (status != ClaimStatus.UNDER_REVIEW) {
            throw invalid("Information can only be requested for a claim under review, but claim is " + status);
        }
        requireAssignedTo(officerId);
        this.status = ClaimStatus.MORE_INFO_REQUIRED;
    }

    /** MORE_INFO_REQUIRED -> UNDER_REVIEW once every request has been answered. */
    public void resumeAfterInformation() {
        if (status != ClaimStatus.MORE_INFO_REQUIRED) {
            throw invalid("Information can only be supplied for a claim that needs more information, but claim is " + status);
        }
        this.status = ClaimStatus.UNDER_REVIEW;
    }

    /** Guard used before accepting an answer, so a wrong-state claim fails before anything is changed. */
    public void requireMoreInfoRequired() {
        if (status != ClaimStatus.MORE_INFO_REQUIRED) {
            throw invalid("Information can only be supplied for a claim that needs more information, but claim is " + status);
        }
    }

    /**
     * UNDER_REVIEW -> APPROVED (with {@code approvedAmount}) or REJECTED (without). Only the officer the claim is assigned
     * to may decide it. APPROVED is not final: the claim still has to be settled.
     */
    public void decide(ClaimStatus outcome, String reason, BigDecimal approvedAmount, String officerId) {
        requireUnderReview();
        if (outcome != ClaimStatus.APPROVED && outcome != ClaimStatus.REJECTED) {
            throw new IllegalArgumentException("A decision is APPROVED or REJECTED, not " + outcome);
        }
        requireAssignedTo(officerId);
        this.status = outcome;
        this.decisionReason = reason;
        this.approvedAmount = approvedAmount;
        this.decidedByOfficerId = officerId;
        this.decidedAt = Instant.now();
    }

    /** APPROVED -> SETTLED (terminal). A rejected claim, or one not yet approved, cannot be settled. */
    public void settle(String officerId) {
        requireApproved();
        requireAssignedTo(officerId);
        this.status = ClaimStatus.SETTLED;
        this.settledByOfficerId = officerId;
        this.settledAt = Instant.now();
    }

    /** Checked first by the decision workflow so a duplicate decision is a 409 even if the body is also wrong. */
    public void requireUnderReview() {
        if (status != ClaimStatus.UNDER_REVIEW) {
            throw invalid("A decision can only be recorded for a claim under review, but claim is " + status);
        }
    }

    /** Checked first by the settlement workflow, for the same reason. */
    public void requireApproved() {
        if (status != ClaimStatus.APPROVED) {
            throw invalid(status == ClaimStatus.REJECTED
                    ? "A rejected claim cannot be settled"
                    : "A claim can only be settled after it is approved, but claim is " + status);
        }
    }

    // ---- claimant edits ----

    /**
     * Changes the details a claimant may correct, only while the claim is still SUBMITTED (once an officer has started
     * on it the claimant's account of it is fixed, and they add to it through information requests instead). A {@code null}
     * argument leaves that field as it is. Returns one line per field that really changed, for the edit history; empty when
     * nothing changed. Free text is reported as "changed" so personal data is not copied into the history.
     */
    public List<String> updateDetails(String claimantName, String claimantEmail, String policyNumber, ClaimType claimType,
                                      String incidentDescription, LocalDate incidentDate, BigDecimal claimedAmount) {
        if (status != ClaimStatus.SUBMITTED) {
            throw ApiException.conflict("CLAIM_NOT_EDITABLE",
                    "A claim can only be changed while it is SUBMITTED, but claim is " + status);
        }
        List<String> changes = new ArrayList<>();
        if (claimantName != null && !claimantName.equals(this.claimantName)) {
            this.claimantName = claimantName;
            changes.add("claimantName: changed");
        }
        if (claimantEmail != null && !claimantEmail.equals(this.claimantEmail)) {
            this.claimantEmail = claimantEmail;
            changes.add("claimantEmail: changed");
        }
        if (policyNumber != null && !policyNumber.equals(this.policyNumber)) {
            changes.add("policyNumber: " + (this.policyNumber == null ? "(none)" : this.policyNumber) + " -> " + policyNumber);
            this.policyNumber = policyNumber;
        }
        if (claimType != null && claimType != this.claimType) {
            changes.add("claimType: " + this.claimType + " -> " + claimType);
            this.claimType = claimType;
        }
        if (incidentDescription != null && !incidentDescription.equals(this.incidentDescription)) {
            this.incidentDescription = incidentDescription;
            changes.add("incidentDescription: changed");
        }
        if (incidentDate != null && !incidentDate.equals(this.incidentDate)) {
            changes.add("incidentDate: " + this.incidentDate + " -> " + incidentDate);
            this.incidentDate = incidentDate;
        }
        if (claimedAmount != null && claimedAmount.compareTo(this.claimedAmount) != 0) {
            changes.add("claimedAmount: " + this.claimedAmount.toPlainString() + " -> " + claimedAmount.toPlainString());
            this.claimedAmount = claimedAmount;
        }
        return changes;
    }

    private void requireAssignedTo(String officerId) {
        if (assignedOfficerId != null && !Objects.equals(assignedOfficerId, officerId)) {
            // the message does not name the assigned officer: the caller may have lost a race for a claim they can no longer see
            throw ApiException.conflict("OFFICER_MISMATCH", "Claim is assigned to a different officer than " + officerId);
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
    public String getClaimantId() { return claimantId; }
    public String getClaimantName() { return claimantName; }
    public String getClaimantEmail() { return claimantEmail; }
    public String getPolicyNumber() { return policyNumber; }
    public String getMarket() { return market; }
    public ClaimType getClaimType() { return claimType; }
    public String getIncidentDescription() { return incidentDescription; }
    public LocalDate getIncidentDate() { return incidentDate; }
    public BigDecimal getClaimedAmount() { return claimedAmount; }
    public String getCurrency() { return currency; }
    public ClaimStatus getStatus() { return status; }
    public String getAssignedOfficerId() { return assignedOfficerId; }
    public String getDecisionReason() { return decisionReason; }
    public BigDecimal getApprovedAmount() { return approvedAmount; }
    public String getDecidedByOfficerId() { return decidedByOfficerId; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getSettledByOfficerId() { return settledByOfficerId; }
    public Instant getSettledAt() { return settledAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
