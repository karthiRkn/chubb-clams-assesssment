package com.chubb.claims.information;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class InformationDtos {

    private InformationDtos() {
    }

    public record CreateInformationRequest(
            @NotBlank @Size(max = 2000) String question,
            @NotBlank @Size(max = 50) String requestedByOfficerId) {
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
            return new InformationRequestResponse(r.getInformationRequestId(), r.getClaim().getClaimId(), r.getQuestion(),
                    r.getStatus(), r.getRequestedByOfficerId(), r.getCreatedAt(), r.getAnswer(), r.getAnsweredAt());
        }
    }

    /** README example for endpoint 8 returns only these fields. */
    public record AnswerResponse(
            String informationRequestId,
            String claimId,
            InformationRequestStatus status,
            String answer,
            Instant answeredAt) {

        public static AnswerResponse from(InformationRequest r) {
            return new AnswerResponse(r.getInformationRequestId(), r.getClaim().getClaimId(), r.getStatus(),
                    r.getAnswer(), r.getAnsweredAt());
        }
    }
}
