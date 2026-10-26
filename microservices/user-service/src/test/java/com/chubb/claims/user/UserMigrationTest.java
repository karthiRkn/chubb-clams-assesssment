package com.chubb.claims.user;

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
 * V2 renames the role OFFICER to CLAIMS_OFFICER and adds the active flag. The Spring tests only ever start from an empty
 * database, so this builds a database as it was after V1, puts an old row in it, and runs V2 over it: that is the case
 * of a database that already holds users (the PostgreSQL one a person has been using).
 */
class UserMigrationTest {

    private static final String LOCATION = "classpath:db/migration/user";

    @Test
    void v2KeepsEveryExistingUserRenamesTheOfficerRoleAndMakesThemActive() throws Exception {
        String url = "jdbc:h2:mem:migration_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations(LOCATION).target("1").load().migrate();

        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            for (String[] user : new String[][] {{"officer1", "OFFICER"}, {"manager1", "MANAGER"}, {"claimant1", "CLAIMANT"},
                    {"admin1", "ADMIN"}}) {
                s.executeUpdate("insert into users (username, password_hash, role, created_at) values ('" + user[0]
                        + "', 'x', '" + user[1] + "', current_timestamp)");
            }

            Flyway.configure().dataSource(url, "sa", "").locations(LOCATION).load().migrate();

            assertThat(roleOf(s, "officer1")).isEqualTo("CLAIMS_OFFICER");
            assertThat(roleOf(s, "manager1")).isEqualTo("MANAGER");
            assertThat(roleOf(s, "claimant1")).isEqualTo("CLAIMANT");
            assertThat(roleOf(s, "admin1")).isEqualTo("ADMIN");
            try (var rs = s.executeQuery("select count(*) from users where active = true")) {
                rs.next();
                assertThat(rs.getInt(1)).as("every existing user is active after V2").isEqualTo(4);
            }

            // the old name is no longer storable, the new one is
            assertThatThrownBy(() -> s.executeUpdate("insert into users (username, password_hash, role, created_at) "
                    + "values ('old.style', 'x', 'OFFICER', current_timestamp)")).isInstanceOf(SQLException.class);
            s.executeUpdate("insert into users (username, password_hash, role, created_at) "
                    + "values ('new.style', 'x', 'CLAIMS_OFFICER', current_timestamp)");
        }
    }

    private static String roleOf(Statement s, String username) throws SQLException {
        try (var rs = s.executeQuery("select role from users where username = '" + username + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
