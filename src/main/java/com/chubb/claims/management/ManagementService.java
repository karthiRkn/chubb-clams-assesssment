package com.chubb.claims.management;

import com.chubb.claims.claim.ClaimRepository;
import com.chubb.claims.claim.ClaimRepository.OfficerStatusAmount;
import com.chubb.claims.claim.ClaimStatus;
import com.chubb.claims.management.ManagementSummary.CurrencyLiability;
import com.chubb.claims.management.OfficerMetrics.CurrencyTotal;
import com.chubb.claims.management.OfficerMetrics.OfficerEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManagementService {

    static final String LIABILITY_NOTE =
            "Simple estimate: sum of CLAIMED amounts of non-terminal claims, grouped by currency, with no exchange-rate conversion. "
                    + "It is neither the expected settlement nor a booked reserve. Not an actuarial reserve.";

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

    static final String OFFICER_NOTE =
            "Open workload is by CURRENT assignee (no reassignment history); decisions are by the deciding officer. "
                    + "Amounts are grouped by currency and never added across currencies.";

    /** Both aggregates are computed by the database (GROUP BY); only the small per-officer merge happens in memory. */
    @Transactional(readOnly = true)
    public OfficerMetrics officerMetrics() {
        Map<String, Map<String, Long>> openByStatus = new TreeMap<>();
        Map<String, Map<String, CurrencyTotal>> openAmounts = new TreeMap<>();
        for (OfficerStatusAmount r : claims.officerWorkload()) {
            openByStatus.computeIfAbsent(r.getOfficerId(), k -> emptyOpenCounts())
                    .merge(r.getStatus().name(), r.getTotal(), Long::sum);
            addAmount(openAmounts, r);
        }

        Map<String, long[]> decided = new TreeMap<>(); // [settled, rejected]
        Map<String, Map<String, CurrencyTotal>> settledAmounts = new TreeMap<>();
        for (OfficerStatusAmount r : claims.officerDecisions()) {
            long[] counts = decided.computeIfAbsent(r.getOfficerId(), k -> new long[2]);
            if (r.getStatus() == ClaimStatus.SETTLED) {
                counts[0] += r.getTotal();
                addAmount(settledAmounts, r);
            } else {
                counts[1] += r.getTotal();
            }
        }

        Set<String> officers = new TreeSet<>(openByStatus.keySet());
        officers.addAll(decided.keySet());

        List<OfficerEntry> entries = officers.stream().map(id -> {
            Map<String, Long> open = openByStatus.getOrDefault(id, emptyOpenCounts());
            long[] d = decided.getOrDefault(id, new long[2]);
            return new OfficerEntry(id, open.values().stream().mapToLong(Long::longValue).sum(), open,
                    sortedByCurrency(openAmounts.get(id)), d[0] + d[1], d[0], d[1],
                    sortedByCurrency(settledAmounts.get(id)));
        }).toList();
        return new OfficerMetrics(Instant.now(), OFFICER_NOTE, entries);
    }

    private static Map<String, Long> emptyOpenCounts() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (ClaimStatus s : ClaimStatus.values()) {
            if (!s.isTerminal()) {
                m.put(s.name(), 0L);
            }
        }
        return m;
    }

    /** The queries group by status as well, so one currency can arrive several times; combine them per currency. */
    private static void addAmount(Map<String, Map<String, CurrencyTotal>> byOfficer, OfficerStatusAmount r) {
        BigDecimal amount = r.getAmount() == null ? BigDecimal.ZERO : r.getAmount();
        byOfficer.computeIfAbsent(r.getOfficerId(), k -> new TreeMap<>())
                .merge(r.getCurrency(), new CurrencyTotal(r.getCurrency(), r.getTotal(), amount),
                        (a, b) -> new CurrencyTotal(a.currency(), a.claimCount() + b.claimCount(), a.amount().add(b.amount())));
    }

    private static List<CurrencyTotal> sortedByCurrency(Map<String, CurrencyTotal> totals) {
        return totals == null ? List.of() : List.copyOf(totals.values());
    }
}
