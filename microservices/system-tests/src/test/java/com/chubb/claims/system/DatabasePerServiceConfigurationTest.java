package com.chubb.claims.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;

/**
 * Every service has a Flyway "version 1" with different content, and a database keeps ONE history table. Two services in
 * one database therefore fail on the second start with "Migration checksum mismatch for migration version 1", and running
 * Flyway repair only moves the failure to the other service. So each service must have its own database; this checks the
 * packaged configuration, and records the Flyway behaviour that makes it necessary.
 */
class DatabasePerServiceConfigurationTest {

    private static final List<String> SERVICES_WITH_A_DATABASE = List.of("user-service", "claim-service", "assessment-service");

    private static Map<String, String> defaultUrls(String file, String key) throws Exception {
        Map<String, String> urls = new LinkedHashMap<>();
        for (String service : SERVICES_WITH_A_DATABASE) {
            String value = PackagedConfiguration.read(service, file).getProperty(key);
            if (value != null) {
                urls.put(service, PackagedConfiguration.effectiveDefault(value));
            }
        }
        return urls;
    }

    @Test
    void noTwoServicesShareADatabaseByDefault() throws Exception {
        Map<String, String> urls = defaultUrls("application.properties", "spring.datasource.url");
        assertThat(urls.keySet()).as("every service with a database sets spring.datasource.url").containsExactlyElementsOf(SERVICES_WITH_A_DATABASE);
        Map<String, List<String>> byUrl = urls.entrySet().stream().collect(Collectors.groupingBy(Map.Entry::getValue,
                LinkedHashMap::new, Collectors.mapping(Map.Entry::getKey, Collectors.toList())));
        byUrl.forEach((url, services) -> assertThat(services)
                .as("these services would share the database %s and one Flyway history table (each has a version 1)", url)
                .hasSize(1));
    }

    @Test
    void thePostgresProfilesNameOneDatabasePerService() throws Exception {
        Map<String, String> urls = defaultUrls("application-postgres.properties", "spring.datasource.url");
        assertThat(urls).hasSize(3);
        assertThat(urls.values()).as("PostgreSQL database per service: %s", urls).doesNotHaveDuplicates();
    }

    // ---- the Flyway behaviour behind the rule, on a throwaway in-memory database ----

    private static String freshDatabase() {
        return "jdbc:h2:mem:shared_" + java.util.UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    private static Flyway flyway(String url, String location) {
        return Flyway.configure().dataSource(url, "sa", "").locations(location).load();
    }

    @Test
    void twoServicesInOneDatabaseFailOnVersionOneAndRepairOnlyMovesTheFailure() {
        String url = freshDatabase();
        Flyway claim = flyway(url, "classpath:db/migration/claim");
        Flyway user = flyway(url, "classpath:db/migration/user");

        claim.migrate();
        assertThatThrownBy(user::migrate).isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("Migration checksum mismatch for migration version 1");

        // the tempting fix: repair from the failing service's side. It "works" ...
        user.repair();
        // ... by rewriting version 1 as the User Service's, so now the Claim Service is the one that cannot start
        assertThatThrownBy(claim::migrate).isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("Migration checksum mismatch for migration version 1");
        // and the User Service believes it has already created its table, which was never created
        user.migrate();
        assertThat(tableNames(url)).contains("claims").doesNotContain("users");
    }

    @Test
    void oneDatabasePerServiceNeedsNoSpecialSettings() {
        flyway(freshDatabase(), "classpath:db/migration/claim").migrate();
        flyway(freshDatabase(), "classpath:db/migration/user").migrate();
        flyway(freshDatabase(), "classpath:db/migration/assessment").migrate();
    }

    private static List<String> tableNames(String url) {
        try (var connection = java.sql.DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var rows = statement.executeQuery("select table_name from information_schema.tables where table_schema = 'public'")) {
            var names = new java.util.ArrayList<String>();
            while (rows.next()) {
                names.add(rows.getString(1));
            }
            return names;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
