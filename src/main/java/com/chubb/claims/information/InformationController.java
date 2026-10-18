package com.chubb.claims.information;

import com.chubb.claims.information.InformationDtos.AnswerResponse;
import com.chubb.claims.information.InformationDtos.CreateInformationRequest;
import com.chubb.claims.information.InformationDtos.InformationRequestResponse;
import com.chubb.claims.information.InformationDtos.SubmitInformationRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InformationController {

    private final InformationService service;

    public InformationController(InformationService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/officer/claims/{claimId}/information-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public InformationRequestResponse request(@PathVariable String claimId,
                                              @Valid @RequestBody CreateInformationRequest body) {
        return service.request(claimId, body);
    }

    @GetMapping("/api/v1/claims/{claimId}/information-requests")
    public List<InformationRequestResponse> list(@PathVariable String claimId) {
        return service.list(claimId);
    }

    @PostMapping("/api/v1/claims/{claimId}/information")
    public AnswerResponse submit(@PathVariable String claimId, @Valid @RequestBody SubmitInformationRequest body) {
        return service.submit(claimId, body);
    }
}
