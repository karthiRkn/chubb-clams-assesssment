package com.chubb.claims.assessment.information;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InformationRequestRepository extends JpaRepository<InformationRequest, Long> {

    List<InformationRequest> findByClaimIdOrderByCreatedAtAscIdAsc(String claimId);

    /**
     * Row lock on one request, so two answers to the same request are serialised in this service's own database.
     * (The claim row lock lives in the Claim Service and is not available here.)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InformationRequest r where r.informationRequestId = :informationRequestId and r.claimId = :claimId")
    Optional<InformationRequest> findForUpdate(@Param("informationRequestId") String informationRequestId,
                                               @Param("claimId") String claimId);

    boolean existsByClaimIdAndStatus(String claimId, InformationRequestStatus status);
}
