package com.chubb.claims.assessment;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimAssessmentRepository extends JpaRepository<ClaimAssessment, Long> {

    List<ClaimAssessment> findByClaimIdOrderByCreatedAtAscIdAsc(String claimId);
}
