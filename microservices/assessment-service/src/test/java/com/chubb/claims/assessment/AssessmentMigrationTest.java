package com.chubb.claims.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * V2 gives every assessment entry a kind and brings in the information-request tables. The Spring tests only ever start from
 * an empty database, so this builds one as it was after V1, puts an old note in it, and runs V2 over it.
 */
class AssessmentMigrationTest {

    private static final String LOCATION = "classpath:db/migration/assessment";

    @Test
    void v2KeepsEveryExistingNoteAsANoteAndAddsTheInformationRequestTables() throws Exception {
        String url = "jdbc:h2:mem:migration_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations(LOCATION).target("1").load().migrate();

        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.executeUpdate("insert into claim_assessments (claim_id, officer_id, notes, created_at) "
                    + "values ('CLM-2026-000101', 'OFF-1', 'An old note', current_timestamp)");

            Flyway.configure().dataSource(url, "sa", "").locations(LOCATION).load().migrate();

            try (var rs = s.executeQuery("select kind, notes from claim_assessments")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).as("an entry from before V2 is a note").isEqualTo("NOTE");
                assertThat(rs.getString(2)).isEqualTo("An old note");
            }
            s.executeUpdate("insert into claim_assessments (claim_id, officer_id, kind, notes, created_at) "
                    + "values ('CLM-2026-000101', 'OFF-1', 'APPROVED', 'ok', current_timestamp)");
            assertThatThrownBy(() -> s.executeUpdate("insert into claim_assessments (claim_id, officer_id, kind, notes, created_at) "
                    + "values ('CLM-2026-000101', 'OFF-1', 'WHATEVER', 'x', current_timestamp)")).isInstanceOf(SQLException.class);

            try (var rs = s.executeQuery("select nextval('information_request_number_seq')")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(1L);
            }
            s.executeUpdate("insert into information_requests (information_request_id, claim_id, question, status, "
                    + "requested_by_officer_id, created_at) values ('IR-2026-000001', 'CLM-2026-000101', 'Q', 'OPEN', 'OFF-1', current_timestamp)");
            assertThatThrownBy(() -> s.executeUpdate("insert into information_requests (information_request_id, claim_id, question, "
                    + "status, requested_by_officer_id, created_at) values ('IR-2026-000002', 'CLM-2026-000101', 'Q', 'LOST', 'OFF-1', "
                    + "current_timestamp)")).isInstanceOf(SQLException.class);
        }
    }
}
