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

/** One row per edit a claimant made to their claim. Append-only: there are no setters and no update path. */
@Entity
@Table(name = "claim_update_history")
public class ClaimUpdateHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_pk", nullable = false, updatable = false)
    private Claim claim;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @Column(nullable = false, updatable = false, length = 100)
    private String actor;

    /** One line per changed field, separated by a line feed. */
    @Column(name = "change_summary", nullable = false, updatable = false, length = 4000)
    private String changeSummary;

    protected ClaimUpdateHistory() {
    }

    public ClaimUpdateHistory(Claim claim, String actor, String changeSummary) {
        this.claim = claim;
        this.actor = actor;
        this.changeSummary = changeSummary;
        this.changedAt = Instant.now();
    }

    public Claim getClaim() { return claim; }
    public Instant getChangedAt() { return changedAt; }
    public String getActor() { return actor; }
    public String getChangeSummary() { return changeSummary; }
}
