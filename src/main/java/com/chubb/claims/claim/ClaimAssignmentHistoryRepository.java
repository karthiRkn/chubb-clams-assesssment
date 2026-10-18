package com.chubb.claims.claim;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimAssignmentHistoryRepository extends JpaRepository<ClaimAssignmentHistory, Long> {

    @Query("select h from ClaimAssignmentHistory h where h.claim = :claim order by h.changedAt, h.id")
    List<ClaimAssignmentHistory> findAllForClaim(@Param("claim") Claim claim);
}
