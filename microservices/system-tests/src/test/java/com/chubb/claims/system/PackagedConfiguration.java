package com.chubb.claims.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The configuration files each service ships, read from the source tree. The system tests start every service with
 * explicit settings and never load these files, so anything that must hold for the files themselves (one JWT secret, one
 * database per service) is checked by reading them here.
 */
final class PackagedConfiguration {

    /** {@code ${NAME:default}} */
    private static final Pattern PLACEHOLDER_WITH_DEFAULT = Pattern.compile("\\$\\{[A-Za-z0-9_.]+:(.*)}");

    private PackagedConfiguration() {
    }

    /** The {@code microservices} folder, found by walking up from the working directory (Maven and the IDE start in different places). */
    static Path microservices() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("claim-service/src/main/resources"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("the microservices folder, found by walking up from the working directory").isNotNull();
        return dir;
    }

    /** The active (not commented out) lines of {@code <service>/src/main/resources/<file>}. */
    static Properties read(String service, String file) throws IOException {
        Properties properties = new Properties();
        for (String line : Files.readAllLines(microservices().resolve(service + "/src/main/resources/" + file))) {
            String text = line.trim();
            if (!text.isEmpty() && !text.startsWith("#") && text.contains("=")) {
                int equals = text.indexOf('=');
                properties.setProperty(text.substring(0, equals).trim(), text.substring(equals + 1).trim());
            }
        }
        return properties;
    }

    /** What the value is when no environment variable overrides it: the text after {@code NAME:} in {@code ${NAME:default}}, or the value itself. */
    static String effectiveDefault(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = PLACEHOLDER_WITH_DEFAULT.matcher(value);
        return m.matches() ? m.group(1) : value;
    }
}
