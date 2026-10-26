package com.chubb.claims.claim;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimStatusHistoryRepository extends JpaRepository<ClaimStatusHistory, Long> {

    @Query("select h from ClaimStatusHistory h where h.claim = :claim order by h.changedAt, h.id")
    List<ClaimStatusHistory> findAllForClaim(@Param("claim") Claim claim);
}
