package com.chubb.claims.claim;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<Claim, Long>, JpaSpecificationExecutor<Claim> {

    /** Lookup by the PUBLIC business id (never the internal numeric id). */
    Optional<Claim> findByClaimId(String claimId);

    /** Row lock so concurrent transitions on the same claim are serialised. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Claim c where c.claimId = :claimId")
    Optional<Claim> findByClaimIdForUpdate(@Param("claimId") String claimId);

    @Query("select c.status as status, count(c) as total from Claim c group by c.status")
    List<StatusCount> countByStatus();

    /**
     * Claims not yet settled or rejected, per currency. Until a claim is decided it counts at the CLAIMED amount; once it is
     * APPROVED (and not yet settled) it counts at the APPROVED amount, which is what the insurer has committed to pay.
     */
    @Query("""
            select c.currency as currency, count(c) as total,
                   sum(case when c.status = com.chubb.claims.claim.ClaimStatus.APPROVED then c.approvedAmount else c.claimedAmount end) as amount
            from Claim c
            where c.status not in (com.chubb.claims.claim.ClaimStatus.SETTLED, com.chubb.claims.claim.ClaimStatus.REJECTED)
            group by c.currency
            order by c.currency
            """)
    List<CurrencyLiability> outstandingLiabilityByCurrency();

    /** Open (non-terminal) claims per current assignee, status and currency. */
    @Query("""
            select c.assignedOfficerId as officerId, c.status as status, c.currency as currency,
                   count(c) as total, sum(c.claimedAmount) as amount
            from Claim c
            where c.assignedOfficerId is not null
              and c.status not in (com.chubb.claims.claim.ClaimStatus.SETTLED, com.chubb.claims.claim.ClaimStatus.REJECTED)
            group by c.assignedOfficerId, c.status, c.currency
            """)
    List<OfficerStatusAmount> officerWorkload();

    /**
     * Decided claims per deciding officer, current status and currency (amount is the approved amount; null for rejections).
     * An approved claim is APPROVED until it is settled and SETTLED afterwards; both were approved by that officer.
     */
    @Query("""
            select c.decidedByOfficerId as officerId, c.status as status, c.currency as currency,
                   count(c) as total, sum(c.approvedAmount) as amount
            from Claim c
            where c.decidedByOfficerId is not null
            group by c.decidedByOfficerId, c.status, c.currency
            """)
    List<OfficerStatusAmount> officerDecisions();

    interface OfficerStatusAmount {
        String getOfficerId();

        ClaimStatus getStatus();

        String getCurrency();

        long getTotal();

        BigDecimal getAmount();
    }

    interface StatusCount {
        ClaimStatus getStatus();

        long getTotal();
    }

    interface CurrencyLiability {
        String getCurrency();

        long getTotal();

        BigDecimal getAmount();
    }
}
