package com.chubb.claims.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A token is only accepted if the service checking it holds the secret that signed it, so every service must read the SAME
 * variable, and none may ship a secret of its own: a secret written into a file in the repository is a secret everybody
 * who can read the repository has. The other tests pass their secret explicitly and never read these files, so this test
 * reads the packaged configuration of every service and fails if one of them has a literal secret or reads another variable.
 */
class JwtSecretConfigurationTest {

    private static final List<String> SERVICES = List.of("user-service", "claim-service", "assessment-service");
    /** {@code ${JWT_SECRET:}}: the environment variable and nothing else, so a missing variable stops the service from starting. */
    private static final Pattern ENVIRONMENT_ONLY = Pattern.compile("\\$\\{JWT_SECRET:}");

    private static Map<String, String> secretLines() throws IOException {
        Map<String, String> lines = new LinkedHashMap<>();
        for (String service : SERVICES) {
            String value = PackagedConfiguration.read(service, "application.properties").getProperty("claims.security.jwt.secret");
            assertThat(value).as(service + " sets claims.security.jwt.secret").isNotNull();
            lines.put(service, value);
        }
        return lines;
    }

    @Test
    void everyServiceReadsTheSameEnvironmentVariableAndHasNoDefaultSecret() throws Exception {
        Map<String, String> lines = secretLines();
        for (Map.Entry<String, String> line : lines.entrySet()) {
            assertThat(line.getValue()).as(line.getKey() + " takes its secret from JWT_SECRET only, with no default").matches(ENVIRONMENT_ONLY);
        }
        assertThat(lines.values()).as("the same line in every service: %s", lines.keySet()).hasSize(3).containsOnly(lines.get("user-service"));
    }

    @Test
    void noPackagedConfigurationFileOfAnyServiceContainsASecretOrACredentialLiteral() throws Exception {
        for (String service : SERVICES) {
            for (String file : List.of("application.properties", "application-postgres.properties")) {
                Properties properties = PackagedConfiguration.read(service, file);
                for (String key : properties.stringPropertyNames()) {
                    String lower = key.toLowerCase(java.util.Locale.ROOT);
                    if (!(lower.contains("password") || lower.contains("secret"))) {
                        continue;
                    }
                    String value = properties.getProperty(key);
                    String where = service + "/" + file + " " + key;
                    if (key.equals("claims.auth.demo-users.password")) {
                        // the documented, public demo password (README): the one literal that is allowed, because it is not a secret
                        assertThat(value).as(where).isEqualTo("${DEMO_USERS_PASSWORD:password123}");
                    } else if (key.equals("spring.datasource.username")) {
                        continue;
                    } else {
                        assertThat(value).as(where + " is an environment variable (or empty), never a value written in the file")
                                .matches("\\$\\{[A-Z][A-Z0-9_]*:?}|");
                    }
                }
                String url = properties.getProperty("spring.datasource.url");
                if (url != null) {
                    assertThat(url).as(service + "/" + file + " has no user or password in its URL").doesNotContain("password=").doesNotContain("@");
                }
            }
        }
    }

    @Test
    void theEnvironmentVariablesAreNamedAfterTheirServiceSoOneCannotBePointedAtAnothersDatabase() throws Exception {
        Map<String, String> expected = Map.of("user-service", "USER_DB", "claim-service", "CLAIM_DB", "assessment-service", "ASSESSMENT_DB");
        for (Map.Entry<String, String> service : expected.entrySet()) {
            Properties properties = PackagedConfiguration.read(service.getKey(), "application.properties");
            assertThat(properties.getProperty("spring.datasource.url")).startsWith("${" + service.getValue() + "_URL:");
            assertThat(properties.getProperty("spring.datasource.username")).startsWith("${" + service.getValue() + "_USERNAME:");
            assertThat(properties.getProperty("spring.datasource.password")).isEqualTo("${" + service.getValue() + "_PASSWORD:}");
        }
    }
}
