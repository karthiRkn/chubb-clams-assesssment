package com.chubb.claims.information;

import com.chubb.claims.claim.Claim;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InformationRequestRepository extends JpaRepository<InformationRequest, Long> {

    @Query("select r from InformationRequest r join fetch r.claim where r.claim = :claim order by r.createdAt, r.id")
    List<InformationRequest> findAllForClaim(@Param("claim") Claim claim);

    Optional<InformationRequest> findByInformationRequestIdAndClaim(String informationRequestId, Claim claim);

    boolean existsByClaimAndStatus(Claim claim, InformationRequestStatus status);
}
