package com.chubb.claims.information;

import com.chubb.claims.claim.Claim;
import com.chubb.claims.claim.ClaimRepository;
import com.chubb.claims.claim.ClaimService;
import com.chubb.claims.claim.ClaimStatus;
import com.chubb.claims.claim.ReferenceGenerator;
import com.chubb.claims.common.ApiException;
import com.chubb.claims.information.InformationDtos.AnswerResponse;
import com.chubb.claims.information.InformationDtos.CreateInformationRequest;
import com.chubb.claims.information.InformationDtos.InformationRequestResponse;
import com.chubb.claims.information.InformationDtos.SubmitInformationRequest;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InformationService {

    private final ClaimService claimService;
    private final ClaimRepository claims;
    private final InformationRequestRepository requests;
    private final ReferenceGenerator references;

    public InformationService(ClaimService claimService, ClaimRepository claims, InformationRequestRepository requests,
                              ReferenceGenerator references) {
        this.claimService = claimService;
        this.claims = claims;
        this.requests = requests;
        this.references = references;
    }

    /** Officer asks for more information: claim UNDER_REVIEW -> AWAITING_INFORMATION. */
    @Transactional
    public InformationRequestResponse request(String claimId, CreateInformationRequest body) {
        Claim claim = claimService.loadForUpdate(claimId);
        ClaimStatus from = claim.getStatus();
        claim.requestInformation();
        claimService.recordTransition(claim, from, body.requestedByOfficerId());
        InformationRequest request = requests.saveAndFlush(new InformationRequest(references.nextInformationRequestId(),
                claim, body.question().trim(), body.requestedByOfficerId()));
        claims.saveAndFlush(claim);
        return InformationRequestResponse.from(request);
    }

    @Transactional(readOnly = true)
    public List<InformationRequestResponse> list(String claimId) {
        Claim claim = claims.findByClaimId(claimId).orElseThrow(() -> claimNotFound(claimId));
        return requests.findAllForClaim(claim).stream().map(InformationRequestResponse::from).toList();
    }

    /**
     * Claimant answers a request. Once no OPEN requests remain the claim automatically resumes to UNDER_REVIEW,
     * so the officer does not need to call the review endpoint again.
     */
    @Transactional
    public AnswerResponse submit(String claimId, SubmitInformationRequest body) {
        Claim claim = claimService.loadForUpdate(claimId);
        InformationRequest request = requests.findByInformationRequestIdAndClaim(body.informationRequestId(), claim)
                .orElseThrow(() -> ApiException.notFound("INFORMATION_REQUEST_NOT_FOUND",
                        "Information request " + body.informationRequestId() + " was not found for claim " + claimId));
        claim.requireAwaitingInformation();
        request.answer(body.answer().trim());
        requests.saveAndFlush(request);
        if (!requests.existsByClaimAndStatus(claim, InformationRequestStatus.OPEN)) {
            ClaimStatus from = claim.getStatus();
            claim.resumeAfterInformation();
            claimService.recordTransition(claim, from, ClaimService.CLAIMANT);
            claims.saveAndFlush(claim);
        }
        return AnswerResponse.from(request);
    }

    private static ApiException claimNotFound(String claimId) {
        return ApiException.notFound("CLAIM_NOT_FOUND", "Claim " + claimId + " was not found");
    }
}
