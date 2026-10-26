package com.chubb.claims.assessment.information;

import com.chubb.claims.assessment.ActingAs;
import com.chubb.claims.assessment.client.ClaimClient;
import com.chubb.claims.assessment.client.ClaimClient.ClaimStatusView;
import com.chubb.claims.assessment.information.InformationDtos.AnswerResponse;
import com.chubb.claims.assessment.information.InformationDtos.CreateInformationRequest;
import com.chubb.claims.assessment.information.InformationDtos.InformationRequestResponse;
import com.chubb.claims.assessment.information.InformationDtos.SubmitInformationRequest;
import com.chubb.claims.common.ApiException;
import com.chubb.claims.common.security.AuthenticatedUser;
import com.chubb.claims.common.security.ClaimVisibility;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Information requests: an officer asks the claimant a question, the claimant answers. The claim's lifecycle belongs to the
 * Claim Service, so each workflow here changes its own table and asks the Claim Service for the matching transition before
 * the local transaction commits: if the Claim Service refuses or cannot be reached, the local change is rolled back and
 * nothing is stored.
 */
@Service
public class InformationService {

    static final String MORE_INFO_REQUIRED = "MORE_INFO_REQUIRED";

    private final InformationRequestRepository requests;
    private final InformationReferenceGenerator references;
    private final ClaimClient claims;

    public InformationService(InformationRequestRepository requests, InformationReferenceGenerator references, ClaimClient claims) {
        this.requests = requests;
        this.references = references;
        this.claims = claims;
    }

    /**
     * Officer asks for more information: claim UNDER_REVIEW to MORE_INFO_REQUIRED, by the officer the claim is assigned to.
     * The request is inserted first so a failure of this service's own database is found before the claim is touched; the
     * Claim Service then enforces the lifecycle rule (404 unknown claim, 409 wrong status or wrong officer) and a refusal
     * rolls the insert back.
     */
    @Transactional
    public InformationRequestResponse request(AuthenticatedUser caller, String claimId, CreateInformationRequest body) {
        String officerId = ActingAs.officer(caller, body.requestedByOfficerId(), "information request", claimId);
        requireVisible(caller, claims.status(claimId));
        InformationRequest request = requests.saveAndFlush(new InformationRequest(references.nextInformationRequestId(),
                claimId, body.question().trim(), officerId));
        claims.requestInformation(claimId, officerId);
        return InformationRequestResponse.from(request);
    }

    @Transactional(readOnly = true)
    public List<InformationRequestResponse> list(String claimId, AuthenticatedUser caller) {
        requireVisible(caller, claims.status(claimId)); // 404 CLAIM_NOT_FOUND for a claim that does not exist
        return requests.findByClaimIdOrderByCreatedAtAscIdAsc(claimId).stream().map(InformationRequestResponse::from).toList();
    }

    /**
     * Claimant answers a request. Once no OPEN requests remain the claim automatically resumes to UNDER_REVIEW, so the
     * officer does not need to call the review endpoint again.
     *
     * <p>The request row is locked first, so two answers to one request are serialised and the second one is a 409. The
     * lock is held while the Claim Service is called, which keeps "answered" and "claim resumed" together: if the resume
     * is refused or fails, the answer is rolled back and the claimant can retry.
     */
    @Transactional
    public AnswerResponse submit(String claimId, SubmitInformationRequest body, AuthenticatedUser caller) {
        ClaimStatusView claim = claims.status(claimId); // 404 CLAIM_NOT_FOUND comes before 404 for the request
        requireVisible(caller, claim); // before anything is said about the request or the claim's status, and before any row is locked
        Optional<InformationRequest> found = requests.findForUpdate(body.informationRequestId(), claimId);
        InformationRequest request = found.orElseThrow(() -> ApiException.notFound("INFORMATION_REQUEST_NOT_FOUND",
                "Information request " + body.informationRequestId() + " was not found for claim " + claimId));
        if (!MORE_INFO_REQUIRED.equals(claim.status())) {
            throw ApiException.conflict("INVALID_CLAIM_TRANSITION",
                    "Information can only be supplied for a claim that needs more information, but claim is " + claim.status());
        }
        request.answer(body.answer().trim());
        requests.saveAndFlush(request);
        if (!requests.existsByClaimIdAndStatus(claimId, InformationRequestStatus.OPEN)) {
            claims.resumeAfterInformation(claimId, caller.username());
        }
        return AnswerResponse.from(request);
    }

    /** Whether the claim still has an OPEN request: a review may not resume from MORE_INFO_REQUIRED while one is. */
    @Transactional(readOnly = true)
    public boolean hasOpenRequests(String claimId) {
        return requests.existsByClaimIdAndStatus(claimId, InformationRequestStatus.OPEN);
    }

    /** What {@link ClaimVisibility} says: a claim the caller may not see answers exactly as one that does not exist. */
    private static void requireVisible(AuthenticatedUser caller, ClaimStatusView claim) {
        ClaimVisibility.require(caller, claim.claimId(), claim.claimantId(), claim.assignedOfficerId());
    }
}
