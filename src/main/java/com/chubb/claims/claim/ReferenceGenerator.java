package com.chubb.claims.claim;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Year;
import org.springframework.stereotype.Component;

/**
 * Generates public references such as CLM-2026-000101 from database sequences.
 * A sequence is atomic across concurrent transactions and application instances, so it cannot hand out
 * the same number twice (unlike count+1). Numbers are not reused if a transaction rolls back, which is acceptable.
 * The year is only a prefix for readability; the number part keeps increasing across years, so references stay unique.
 */
@Component
public class ReferenceGenerator {

    @PersistenceContext
    private EntityManager entityManager;

    private final Clock clock = Clock.systemUTC();

    public String nextClaimId() {
        return format("CLM", "claim_number_seq");
    }

    public String nextInformationRequestId() {
        return format("IR", "information_request_number_seq");
    }

    private String format(String prefix, String sequence) {
        // sequence name is a compile-time constant from this class, never user input
        Number next = (Number) entityManager.createNativeQuery("select nextval('" + sequence + "')").getSingleResult();
        return "%s-%d-%06d".formatted(prefix, Year.now(clock).getValue(), next.longValue());
    }
}
