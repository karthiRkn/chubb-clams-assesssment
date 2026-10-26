package com.chubb.claims.assessment.information;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Year;
import org.springframework.stereotype.Component;

/**
 * Generates the public information request reference, such as IR-2026-000001, from the database sequence
 * {@code information_request_number_seq}. A sequence is atomic across concurrent transactions and instances (unlike
 * count+1). Numbers are not reused if a transaction rolls back, so gaps are possible.
 */
@Component
public class InformationReferenceGenerator {

    @PersistenceContext
    private EntityManager entityManager;

    private final Clock clock = Clock.systemUTC();

    public String nextInformationRequestId() {
        // sequence name is a compile-time constant, never user input
        Number next = (Number) entityManager.createNativeQuery("select nextval('information_request_number_seq')").getSingleResult();
        return "IR-%d-%06d".formatted(Year.now(clock).getValue(), next.longValue());
    }
}
