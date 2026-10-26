package com.chubb.claims.claim.management;

import com.chubb.claims.claim.ClaimRepository;
import com.chubb.claims.claim.ClaimRepository.OfficerStatusAmount;
import com.chubb.claims.claim.ClaimStatus;
import com.chubb.claims.claim.management.ManagementSummary.CurrencyLiability;
import com.chubb.claims.claim.management.OfficerMetrics.CurrencyTotal;
import com.chubb.claims.claim.management.OfficerMetrics.OfficerEntry;
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

/**
 * The management reports (outstanding liability and per-officer workload). The grouping is done by the database
 * ({@code GROUP BY} in {@link ClaimRepository}); this class only shapes the rows into the report. Each report is one
 * read-only transaction, so its numbers come from one snapshot. Currencies are never added together.
 */
@Service
public class ManagementService {

    static final String LIABILITY_NOTE =
            "Simple estimate: for claims not yet decided, the CLAIMED amount; for APPROVED claims awaiting settlement, the APPROVED "
                    + "amount; settled and rejected claims are left out. Grouped by currency, with no exchange-rate conversion. "
                    + "It is neither the expected settlement nor a booked reserve. Not an actuarial reserve.";

    static final String OFFICER_NOTE =
            "Open workload is by CURRENT assignee (no reassignment history); decisions are by the deciding officer. "
                    + "Amounts are grouped by currency and never added across currencies.";

    private final ClaimRepository claims;

    public ManagementService(ClaimRepository claims) {
        this.claims = claims;
    }

    @Transactional(readOnly = true)
    public ManagementSummary summary() {
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

    /** The aggregates arrive grouped by officer, status and currency; only the small per-officer merge happens here. */
    @Transactional(readOnly = true)
    public OfficerMetrics officerMetrics() {
        Map<String, Map<String, Long>> openByStatus = new TreeMap<>();
        Map<String, Map<String, CurrencyTotal>> openAmounts = new TreeMap<>();
        for (OfficerStatusAmount r : claims.officerWorkload()) {
            openByStatus.computeIfAbsent(r.getOfficerId(), k -> emptyOpenCounts())
                    .merge(r.getStatus().name(), r.getTotal(), Long::sum);
            addAmount(openAmounts, r);
        }

        Map<String, long[]> decided = new TreeMap<>(); // [approved, settled, rejected]
        Map<String, Map<String, CurrencyTotal>> approvedAmounts = new TreeMap<>();
        for (OfficerStatusAmount r : claims.officerDecisions()) {
            long[] counts = decided.computeIfAbsent(r.getOfficerId(), k -> new long[3]);
            if (r.getStatus() == ClaimStatus.REJECTED) {
                counts[2] += r.getTotal();
            } else {
                counts[0] += r.getTotal(); // APPROVED and SETTLED claims were both approved by this officer
                if (r.getStatus() == ClaimStatus.SETTLED) {
                    counts[1] += r.getTotal();
                }
                addAmount(approvedAmounts, r);
            }
        }

        Set<String> officers = new TreeSet<>(openByStatus.keySet());
        officers.addAll(decided.keySet());

        List<OfficerEntry> entries = officers.stream().map(id -> {
            Map<String, Long> open = openByStatus.getOrDefault(id, emptyOpenCounts());
            long[] d = decided.getOrDefault(id, new long[3]);
            return new OfficerEntry(id, open.values().stream().mapToLong(Long::longValue).sum(), open,
                    sortedByCurrency(openAmounts.get(id)), d[0] + d[2], d[0], d[1], d[2],
                    sortedByCurrency(approvedAmounts.get(id)));
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

    /** The rows are grouped by status as well, so one currency can arrive several times; combine them per currency. */
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
