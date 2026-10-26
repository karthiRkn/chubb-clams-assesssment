package com.chubb.claims.assessment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One entry of a claim's assessment history: a note recorded during review, or the decision or settlement that followed,
 * with the officer who did it. Append-only: there are no setters and no update path.
 */
@Entity
@Table(name = "claim_assessments")
public class ClaimAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false, updatable = false, length = 32)
    private String claimId;

    @Column(name = "officer_id", nullable = false, updatable = false, length = 50)
    private String officerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private AssessmentKind kind;

    @Column(nullable = false, updatable = false, length = 2000)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ClaimAssessment() {
    }

    public ClaimAssessment(String claimId, String officerId, AssessmentKind kind, String notes) {
        this.claimId = claimId;
        this.officerId = officerId;
        this.kind = kind;
        this.notes = notes;
        this.createdAt = Instant.now();
    }

    public String getClaimId() { return claimId; }
    public String getOfficerId() { return officerId; }
    public AssessmentKind getKind() { return kind; }
    public String getNotes() { return notes; }
    public Instant getCreatedAt() { return createdAt; }
}
