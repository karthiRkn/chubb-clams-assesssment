package com.chubb.claims.officer;

import com.chubb.claims.claim.Claim;
import com.chubb.claims.claim.ClaimStatus;
import com.chubb.claims.claim.ClaimType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;

public final class OfficerDtos {

    private OfficerDtos() {
    }

    public enum Decision {
        SETTLED(ClaimStatus.SETTLED),
        REJECTED(ClaimStatus.REJECTED);

        private final ClaimStatus status;

        Decision(ClaimStatus status) {
            this.status = status;
        }

        public ClaimStatus toStatus() {
            return status;
        }
    }

    public record OfficerRequest(@NotBlank @Size(max = 50) String officerId) {
    }

    public record DecisionRequest(
            @NotNull Decision decision,
            @NotBlank @Size(max = 2000) String decisionReason,
            @Digits(integer = 17, fraction = 2) BigDecimal settledAmount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter upper-case ISO currency code, e.g. SGD") String currency,
            @NotBlank @Size(max = 50) String decidedByOfficerId) {
    }

    public record WorkflowResponse(String claimId, ClaimStatus status, String assignedOfficerId, Instant updatedAt) {
        public static WorkflowResponse from(Claim c) {
            return new WorkflowResponse(c.getClaimId(), c.getStatus(), c.getAssignedOfficerId(), c.getUpdatedAt());
        }
    }

    public record DecisionResponse(String claimId, ClaimStatus status, String decisionReason, BigDecimal settledAmount,
                                   String currency, String decidedByOfficerId, Instant decidedAt, Instant updatedAt) {
        public static DecisionResponse from(Claim c) {
            return new DecisionResponse(c.getClaimId(), c.getStatus(), c.getDecisionReason(), c.getSettledAmount(),
                    c.getCurrency(), c.getDecidedByOfficerId(), c.getDecidedAt(), c.getUpdatedAt());
        }
    }

    public record ClaimSummary(Long id, String claimId, String claimantName, String market, ClaimType claimType,
                               BigDecimal claimedAmount, String currency, ClaimStatus status, String assignedOfficerId,
                               Instant createdAt) {
        public static ClaimSummary from(Claim c) {
            return new ClaimSummary(c.getId(), c.getClaimId(), c.getClaimantName(), c.getMarket(), c.getClaimType(),
                    c.getClaimedAmount(), c.getCurrency(), c.getStatus(), c.getAssignedOfficerId(), c.getCreatedAt());
        }
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <T> PageResponse<T> from(Page<T> p) {
            return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
        }
    }
}
