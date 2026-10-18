package com.chubb.claims.officer;

import com.chubb.claims.claim.Claim;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimAssessmentRepository extends JpaRepository<ClaimAssessment, Long> {

    @Query("select a from ClaimAssessment a where a.claim = :claim order by a.createdAt, a.id")
    List<ClaimAssessment> findAllForClaim(@Param("claim") Claim claim);
}
