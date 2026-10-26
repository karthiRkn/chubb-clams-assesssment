package com.chubb.claims.assessment.information;

import com.chubb.claims.common.OfficerIds;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class InformationDtos {

    private InformationDtos() {
    }

    /**
     * {@code requestedByOfficerId} may be left out by a claims officer (it is their own login name, from the token); naming
     * another officer is 403, and an administrator acting for an officer must name one.
     */
    public record CreateInformationRequest(
            @NotBlank @Size(max = 2000) String question,
            @Size(max = 50) @Pattern(regexp = OfficerIds.NOT_PLACEHOLDER, message = OfficerIds.PLACEHOLDER_MESSAGE) String requestedByOfficerId) {
    }

    public record SubmitInformationRequest(
            @NotBlank @Size(max = 32) String informationRequestId,
            @NotBlank @Size(max = 4000) String answer) {
    }

    public record InformationRequestResponse(
            String informationRequestId,
            String claimId,
            String question,
            InformationRequestStatus status,
            String requestedByOfficerId,
            Instant createdAt,
            String answer,
            Instant answeredAt) {

        public static InformationRequestResponse from(InformationRequest r) {
            return new InformationRequestResponse(r.getInformationRequestId(), r.getClaimId(), r.getQuestion(),
                    r.getStatus(), r.getRequestedByOfficerId(), r.getCreatedAt(), r.getAnswer(), r.getAnsweredAt());
        }
    }

    public record AnswerResponse(
            String informationRequestId,
            String claimId,
            InformationRequestStatus status,
            String answer,
            Instant answeredAt) {

        public static AnswerResponse from(InformationRequest r) {
            return new AnswerResponse(r.getInformationRequestId(), r.getClaimId(), r.getStatus(),
                    r.getAnswer(), r.getAnsweredAt());
        }
    }
}
