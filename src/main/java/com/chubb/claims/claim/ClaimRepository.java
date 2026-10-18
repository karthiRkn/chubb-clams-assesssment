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

    @Query("""
            select c.currency as currency, count(c) as total, sum(c.claimedAmount) as amount
            from Claim c
            where c.status not in (com.chubb.claims.claim.ClaimStatus.SETTLED, com.chubb.claims.claim.ClaimStatus.REJECTED)
            group by c.currency
            order by c.currency
            """)
    List<CurrencyLiability> outstandingLiabilityByCurrency();

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
