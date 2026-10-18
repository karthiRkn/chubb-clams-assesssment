package com.chubb.claims.management;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record OfficerMetrics(
        Instant generatedAt,
        String note,
        List<OfficerEntry> officers) {

    public record OfficerEntry(
            String officerId,
            long openClaimCount,
            Map<String, Long> openClaimsByStatus,
            List<CurrencyTotal> openClaimedAmountByCurrency,
            long decidedCount,
            long settledCount,
            long rejectedCount,
            List<CurrencyTotal> settledAmountByCurrency) {
    }

    public record CurrencyTotal(String currency, long claimCount, BigDecimal amount) {
    }
}
