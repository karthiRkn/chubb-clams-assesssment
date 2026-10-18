package com.chubb.claims.management;

import com.chubb.claims.claim.ClaimRepository;
import com.chubb.claims.claim.ClaimStatus;
import com.chubb.claims.management.ManagementSummary.CurrencyLiability;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManagementService {

    static final String LIABILITY_NOTE =
            "Simple estimate: sum of claimed amounts of non-terminal claims, grouped by currency. Not an actuarial reserve.";

    private final ClaimRepository claims;

    public ManagementService(ClaimRepository claims) {
        this.claims = claims;
    }

    @Transactional(readOnly = true)
    public ManagementSummary summary() {
        // Both aggregates are computed by the database (GROUP BY); currencies are never added together.
        List<CurrencyLiability> liability = claims.outstandingLiabilityByCurrency().stream()
                .map(r -> new CurrencyLiability(r.getCurrency(), r.getTotal(), r.getAmount()))
                .toList();

        Map<String, Long> counts = new LinkedHashMap<>();
        for (ClaimStatus s : ClaimStatus.values()) {
            counts.put(s.name(), 0L);
        }
        claims.countByStatus().forEach(r -> counts.put(r.getStatus().name(), r.getTotal()));

        long outstanding = counts.entrySet().stream()
                .filter(e -> !ClaimStatus.valueOf(e.getKey()).isTerminal())
                .mapToLong(Map.Entry::getValue).sum();

        return new ManagementSummary(Instant.now(), LIABILITY_NOTE, outstanding, liability, counts);
    }
}
