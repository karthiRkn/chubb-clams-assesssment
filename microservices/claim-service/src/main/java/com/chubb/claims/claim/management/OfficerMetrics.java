package com.chubb.claims.claim.management;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record OfficerMetrics(
        Instant generatedAt,
        String note,
        List<OfficerEntry> officers) {

    /**
     * {@code approvedCount} counts every claim the officer approved, whether it is still APPROVED or has since been SETTLED;
     * {@code settledCount} is the part of those that is settled. {@code decidedCount} is approvals plus rejections.
     */
    public record OfficerEntry(
            String officerId,
            long openClaimCount,
            Map<String, Long> openClaimsByStatus,
            List<CurrencyTotal> openClaimedAmountByCurrency,
            long decidedCount,
            long approvedCount,
            long settledCount,
            long rejectedCount,
            List<CurrencyTotal> approvedAmountByCurrency) {
    }

    public record CurrencyTotal(String currency, long claimCount, BigDecimal amount) {
    }
}
