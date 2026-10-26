package com.chubb.claims.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * V5 changes what is stored for claims that already exist: one status is renamed (in the claims and in their history), three
 * columns are renamed, a settled claim keeps its amount as the approved amount and gets its settlement filled in. The Spring
 * tests only ever start from an empty database, so this builds one as it was after V4, fills it with old claims, and runs V5
 * over it: the situation of a database that has been in use (the PostgreSQL one a person has been running).
 */
class ClaimMigrationTest {

    private static final String LOCATION = "classpath:db/migration/claim";

    private static void insertClaim(Statement s, String claimId, String status, String settledAmount, String decidedBy, String createdBy)
            throws SQLException {
        s.executeUpdate("insert into claims (claim_id, claimant_name, claimant_email, market, claim_type, description, incident_date, "
                + "claimed_amount, currency, status, settled_amount, decided_by_officer_id, decided_at, created_by, created_at, updated_at) "
                + "values ('" + claimId + "', 'Mei Tan', 'mei@example.com', 'SG', 'MOTOR', 'text of " + claimId + "', date '2026-10-12', 1000.00, "
                + "'INR', '" + status + "', " + settledAmount + ", " + decidedBy + ", "
                + (decidedBy.equals("null") ? "null" : "timestamp with time zone '2026-10-20 10:00:00+00'") + ", " + createdBy
                + ", current_timestamp, current_timestamp)");
    }

    private static void insertHistory(Statement s, String claimId, String from, String to) throws SQLException {
        s.executeUpdate("insert into claim_status_history (claim_pk, from_status, to_status, changed_at, actor) "
                + "select id, " + from + ", '" + to + "', current_timestamp, 'x' from claims where claim_id = '" + claimId + "'");
    }

    private static String one(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).as(sql).isTrue();
            return rs.getString(1);
        }
    }

    @Test
    void v5KeepsEveryExistingClaimRenamesWhatChangedAndFillsInTheSettlementOfSettledClaims() throws Exception {
        String url = "jdbc:h2:mem:migration_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations(LOCATION).target("4").load().migrate();

        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            insertClaim(s, "CLM-2026-000101", "AWAITING_INFORMATION", "null", "null", "'claimant.a'");
            insertClaim(s, "CLM-2026-000102", "SETTLED", "900.00", "'OFF-1'", "null"); // settled in one step, before owners were recorded
            insertClaim(s, "CLM-2026-000103", "UNDER_REVIEW", "null", "null", "'claimant.b'");
            insertClaim(s, "CLM-2026-000104", "REJECTED", "null", "'OFF-2'", "'claimant.c'");
            insertHistory(s, "CLM-2026-000101", "null", "SUBMITTED");
            insertHistory(s, "CLM-2026-000101", "'SUBMITTED'", "UNDER_REVIEW");
            insertHistory(s, "CLM-2026-000101", "'UNDER_REVIEW'", "AWAITING_INFORMATION");
            insertHistory(s, "CLM-2026-000102", "'UNDER_REVIEW'", "SETTLED");

            Flyway.configure().dataSource(url, "sa", "").locations(LOCATION).load().migrate();

            // the status label changed, the others did not
            assertThat(one(s, "select status from claims where claim_id = 'CLM-2026-000101'")).isEqualTo("MORE_INFO_REQUIRED");
            assertThat(one(s, "select status from claims where claim_id = 'CLM-2026-000102'")).isEqualTo("SETTLED");
            assertThat(one(s, "select status from claims where claim_id = 'CLM-2026-000103'")).isEqualTo("UNDER_REVIEW");
            assertThat(one(s, "select status from claims where claim_id = 'CLM-2026-000104'")).isEqualTo("REJECTED");
            assertThat(one(s, "select count(*) from claims")).isEqualTo("4");

            // the history says the same thing, and nothing was added or lost
            assertThat(one(s, "select count(*) from claim_status_history")).isEqualTo("4");
            assertThat(one(s, "select count(*) from claim_status_history where to_status = 'AWAITING_INFORMATION' "
                    + "or from_status = 'AWAITING_INFORMATION'")).isEqualTo("0");
            assertThat(one(s, "select to_status from claim_status_history h join claims c on c.id = h.claim_pk "
                    + "where c.claim_id = 'CLM-2026-000101' order by h.id desc limit 1")).isEqualTo("MORE_INFO_REQUIRED");
            assertThat(one(s, "select from_status || '>' || to_status from claim_status_history h join claims c on c.id = h.claim_pk "
                    + "where c.claim_id = 'CLM-2026-000102'")).as("a claim settled in one step keeps that history").isEqualTo("UNDER_REVIEW>SETTLED");

            // renamed columns keep their values
            assertThat(one(s, "select incident_description from claims where claim_id = 'CLM-2026-000103'")).isEqualTo("text of CLM-2026-000103");
            assertThat(one(s, "select claimant_id from claims where claim_id = 'CLM-2026-000101'")).isEqualTo("claimant.a");
            assertThat(one(s, "select claimant_id from claims where claim_id = 'CLM-2026-000102'")).as("no recorded owner stays none").isNull();
            assertThat(new BigDecimal(one(s, "select approved_amount from claims where claim_id = 'CLM-2026-000102'")))
                    .as("the old settled amount is now the approved amount").isEqualByComparingTo("900.00");

            // a settled claim was decided and settled by the same officer at the same moment; nobody else has a settlement
            assertThat(one(s, "select settled_by_officer_id from claims where claim_id = 'CLM-2026-000102'")).isEqualTo("OFF-1");
            assertThat(one(s, "select cast(settled_at as varchar) from claims where claim_id = 'CLM-2026-000102'")).startsWith("2026-10-20 10:00:00");
            assertThat(one(s, "select count(*) from claims where settled_at is not null")).isEqualTo("1");
            assertThat(one(s, "select policy_number from claims where claim_id = 'CLM-2026-000101'")).as("old claims have no policy number").isNull();

            // the constraint now allows the six statuses and not the old label
            s.executeUpdate("update claims set status = 'APPROVED' where claim_id = 'CLM-2026-000103'");
            assertThatThrownBy(() -> s.executeUpdate("update claims set status = 'AWAITING_INFORMATION' where claim_id = 'CLM-2026-000103'"))
                    .isInstanceOf(SQLException.class);

            // the edit history table exists and enforces its foreign key
            s.executeUpdate("insert into claim_update_history (claim_pk, changed_at, actor, change_summary) "
                    + "select id, current_timestamp, 'claimant.a', 'claimedAmount: 1 -> 2' from claims where claim_id = 'CLM-2026-000101'");
            assertThatThrownBy(() -> s.executeUpdate("insert into claim_update_history (claim_pk, changed_at, actor, change_summary) "
                    + "values (999999, current_timestamp, 'x', 'y')")).isInstanceOf(SQLException.class);
        }
    }
}
