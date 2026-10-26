package com.chubb.claims.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chubb.claims.common.ApiException;
import com.chubb.claims.common.security.AuthenticatedUser;
import com.chubb.claims.common.security.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * An administrator acting for an officer is recorded in the history as that officer, so the log is where the administrator's
 * own name is kept. No Spring context: the class is plain code and a log appender.
 */
class ActingAsTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(ActingAs.class);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void capture() {
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
    }

    private java.util.List<String> lines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void anAdministratorActingForAnOfficerLeavesALineNamingBothAndTheClaim() {
        String officer = ActingAs.officer(new AuthenticatedUser("admin1", Role.ADMIN), "off-1007", "decision APPROVED", "CLM-2026-000101");
        assertThat(officer).isEqualTo("off-1007");
        assertThat(lines()).hasSize(1);
        assertThat(lines().get(0)).contains("ADMIN").contains("admin1").contains("decision APPROVED").contains("CLM-2026-000101")
                .contains("off-1007");
    }

    @Test
    void anOfficerActingAsThemselvesLeavesNoLineBecauseTheHistoryAlreadyNamesThem() {
        assertThat(ActingAs.officer(new AuthenticatedUser("officer1", Role.CLAIMS_OFFICER), null, "review", "CLM-2026-000101"))
                .isEqualTo("officer1");
        assertThat(ActingAs.officer(new AuthenticatedUser("officer1", Role.CLAIMS_OFFICER), "officer1", "review", "CLM-2026-000101"))
                .isEqualTo("officer1");
        assertThat(lines()).isEmpty();
    }

    @Test
    void aRefusedRequestLeavesNoLineAndTheRefusalIsTheSharedRule() {
        assertThatThrownBy(() -> ActingAs.officer(new AuthenticatedUser("officer1", Role.CLAIMS_OFFICER), "officer2", "review", "CLM-1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("OFFICER_IDENTITY_MISMATCH"));
        assertThatThrownBy(() -> ActingAs.officer(new AuthenticatedUser("admin1", Role.ADMIN), null, "review", "CLM-1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("VALIDATION_FAILED"));
        assertThat(lines()).isEmpty();
    }

    @Test
    void aLineBreakInAnOfficerNameCannotForgeALogLine() {
        ActingAs.officer(new AuthenticatedUser("admin1", Role.ADMIN), "off-1\nINFO fake line: admin2 did something", "review", "CLM-1\r\n");
        assertThat(lines()).hasSize(1);
        assertThat(lines().get(0)).doesNotContain("\n").doesNotContain("\r");
    }
}
