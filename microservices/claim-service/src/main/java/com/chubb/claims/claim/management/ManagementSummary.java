package com.chubb.claims.claim.management;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ManagementSummary(
        Instant generatedAt,
        String liabilityNote,
        long outstandingClaimCount,
        List<CurrencyLiability> outstandingLiabilityByCurrency,
        Map<String, Long> countsByStatus) {

    public record CurrencyLiability(String currency, long claimCount, BigDecimal estimatedLiability) {
    }
}
