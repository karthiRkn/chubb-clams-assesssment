package com.chubb.claims.officer;

import com.chubb.claims.claim.Claim;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** One assessment note recorded during review. Append-only: there are no setters and no update path. */
@Entity
@Table(name = "claim_assessments")
public class ClaimAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_pk", nullable = false, updatable = false)
    private Claim claim;

    @Column(name = "officer_id", nullable = false, updatable = false, length = 50)
    private String officerId;

    @Column(nullable = false, updatable = false, length = 2000)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ClaimAssessment() {
    }

    public ClaimAssessment(Claim claim, String officerId, String notes) {
        this.claim = claim;
        this.officerId = officerId;
        this.notes = notes;
        this.createdAt = Instant.now();
    }

    public Claim getClaim() { return claim; }
    public String getOfficerId() { return officerId; }
    public String getNotes() { return notes; }
    public Instant getCreatedAt() { return createdAt; }
}
