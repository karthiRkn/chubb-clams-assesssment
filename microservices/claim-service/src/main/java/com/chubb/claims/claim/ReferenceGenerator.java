package com.chubb.claims.claim;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Year;
import org.springframework.stereotype.Component;

/**
 * Generates the public claim reference, such as CLM-2026-000101, from the database sequence {@code claim_number_seq}.
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
        // sequence name is a compile-time constant, never user input
        Number next = (Number) entityManager.createNativeQuery("select nextval('claim_number_seq')").getSingleResult();
        return "CLM-%d-%06d".formatted(Year.now(clock).getValue(), next.longValue());
    }
}
