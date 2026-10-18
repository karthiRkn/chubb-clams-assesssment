package com.chubb.claims.claim;

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

/** One row per change of assigned officer. Append-only: there are no setters and no update path. */
@Entity
@Table(name = "claim_assignment_history")
public class ClaimAssignmentHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_pk", nullable = false, updatable = false)
    private Claim claim;

    @Column(name = "previous_officer_id", updatable = false, length = 50)
    private String previousOfficerId;

    @Column(name = "new_officer_id", nullable = false, updatable = false, length = 50)
    private String newOfficerId;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @Column(updatable = false, length = 50)
    private String actor;

    protected ClaimAssignmentHistory() {
    }

    public ClaimAssignmentHistory(Claim claim, String previousOfficerId, String newOfficerId, String actor) {
        this.claim = claim;
        this.previousOfficerId = previousOfficerId;
        this.newOfficerId = newOfficerId;
        this.actor = actor;
        this.changedAt = Instant.now();
    }

    public Claim getClaim() { return claim; }
    public String getPreviousOfficerId() { return previousOfficerId; }
    public String getNewOfficerId() { return newOfficerId; }
    public Instant getChangedAt() { return changedAt; }
    public String getActor() { return actor; }
}
