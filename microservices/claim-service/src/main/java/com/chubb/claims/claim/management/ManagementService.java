package com.chubb.claims.claim.management;

import com.chubb.claims.claim.ClaimRepository;
import com.chubb.claims.claim.ClaimRepository.OfficerStatusAmount;
import com.chubb.claims.claim.ClaimStatus;
import com.chubb.claims.claim.client.UserClient;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
                    + "Every active claims officer is listed, with zeros when nothing is assigned to them; an officer who has been "
                    + "switched off is listed only while they still have open or decided claims. "
                    + "Amounts are grouped by currency and never added across currencies.";

    private final ClaimRepository claims;
    private final UserClient users;
    private final TransactionTemplate readOnly;

    public ManagementService(ClaimRepository claims, UserClient users, PlatformTransactionManager transactions) {
        this.claims = claims;
        this.users = users;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
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

    /**
     * One entry per officer: every active claims officer (the User Service owns the accounts, so it is asked, and a claims
     * query alone would never see an officer who has no claims) together with anyone who appears on a claim, such as an
     * officer switched off since they were assigned work. The User Service is asked first, outside any transaction, so no
     * database transaction stays open across that call; the claims are then read in one read-only transaction.
     */
    public OfficerMetrics officerMetrics() {
        List<String> activeOfficers = users.activeClaimsOfficers();
        return readOnly.execute(status -> officerMetrics(activeOfficers));
    }

    /** The aggregates arrive grouped by officer, status and currency; only the small per-officer merge happens here. */
    private OfficerMetrics officerMetrics(List<String> activeOfficers) {
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

        Set<String> officers = new TreeSet<>(activeOfficers);
        officers.addAll(openByStatus.keySet());
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
