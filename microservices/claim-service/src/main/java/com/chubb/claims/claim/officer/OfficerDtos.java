package com.chubb.claims.claim.officer;

import com.chubb.claims.claim.Claim;
import com.chubb.claims.claim.ClaimStatus;
import com.chubb.claims.claim.ClaimType;
import com.chubb.claims.common.OfficerIds;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;

public final class OfficerDtos {

    private OfficerDtos() {
    }

    /** The officer to assign the claim to: a claims officer's own login name (an officer may only name themselves). */
    public record OfficerRequest(@NotBlank @Size(max = 50) @Pattern(regexp = OfficerIds.NOT_PLACEHOLDER, message = OfficerIds.PLACEHOLDER_MESSAGE) String officerId) {
    }

    public record ClaimSummary(Long id, String claimId, String claimantId, String claimantName, String policyNumber,
                               String market, ClaimType claimType, BigDecimal claimedAmount, BigDecimal approvedAmount,
                               String currency, ClaimStatus status, String assignedOfficerId, Instant createdAt) {
        public static ClaimSummary from(Claim c) {
            return new ClaimSummary(c.getId(), c.getClaimId(), c.getClaimantId(), c.getClaimantName(), c.getPolicyNumber(),
                    c.getMarket(), c.getClaimType(), c.getClaimedAmount(), c.getApprovedAmount(), c.getCurrency(),
                    c.getStatus(), c.getAssignedOfficerId(), c.getCreatedAt());
        }
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <T> PageResponse<T> from(Page<T> p) {
            return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
        }
    }
}
