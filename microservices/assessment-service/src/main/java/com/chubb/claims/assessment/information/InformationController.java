package com.chubb.claims.assessment.information;

import com.chubb.claims.assessment.information.InformationDtos.AnswerResponse;
import com.chubb.claims.assessment.information.InformationDtos.CreateInformationRequest;
import com.chubb.claims.assessment.information.InformationDtos.InformationRequestResponse;
import com.chubb.claims.assessment.information.InformationDtos.SubmitInformationRequest;
import com.chubb.claims.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Information requests: the officer asks, the claimant answers. Text only: there is no document upload. */
@RestController
public class InformationController {

    private final InformationService service;

    public InformationController(InformationService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/officer/claims/{claimId}/information-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ask the claimant for more information (UNDER_REVIEW to MORE_INFO_REQUIRED)",
            description = "Only by the officer the claim is assigned to.")
    public InformationRequestResponse request(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser caller,
                                              @PathVariable String claimId, @Valid @RequestBody CreateInformationRequest body) {
        return service.request(caller, claimId, body);
    }

    @GetMapping("/api/v1/claims/{claimId}/information-requests")
    @Operation(summary = "The information requests of a claim and their answers",
            description = "A claimant sees only their own claim's; a claims officer only unassigned claims and their own.")
    public List<InformationRequestResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser caller,
                                                 @PathVariable String claimId) {
        return service.list(claimId, caller);
    }

    @PostMapping("/api/v1/claims/{claimId}/information")
    @Operation(summary = "Answer an information request (claimant)",
            description = "When the last open request is answered the claim goes back to UNDER_REVIEW by itself.")
    public AnswerResponse submit(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser caller,
                                 @PathVariable String claimId, @Valid @RequestBody SubmitInformationRequest body) {
        return service.submit(claimId, body, caller);
    }
}
