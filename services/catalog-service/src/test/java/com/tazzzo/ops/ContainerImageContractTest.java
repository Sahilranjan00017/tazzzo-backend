package com.tazzzo.ops;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the deployable shape: the image runs unprivileged, carries no configuration or secrets, admits only the built
 * jar into its build context, and the application drains in-flight requests on SIGTERM within a bounded grace period.
 */
class ContainerImageContractTest {

    static final Path DOCKERFILE = Path.of("Dockerfile");
    static final Path IGNORE = Path.of(".dockerignore");

    @Test
    void the_image_runs_as_a_fixed_unprivileged_user_with_the_boot_launcher() throws IOException {
        String d = Files.readString(DOCKERFILE);
        List<String> users = d.lines().filter(l -> l.startsWith("USER ")).toList();
        assertThat(users).containsExactly("USER 10001:10001");
        assertThat(d.lines().filter(l -> l.startsWith("ENTRYPOINT")).toList())
                .containsExactly("ENTRYPOINT [\"java\", \"org.springframework.boot.loader.launch.JarLauncher\"]");
        assertThat(d.indexOf("USER 10001:10001")).as("USER is set after every COPY").isGreaterThan(d.lastIndexOf("COPY "));
        assertThat(d).contains("-XX:+ExitOnOutOfMemoryError").contains("-XX:MaxRAMPercentage=");
        assertThat(d).containsPattern("(?m)^ARG JRE_IMAGE=eclipse-temurin:21-jre[^@\\s]*@sha256:[0-9a-f]{64}$");
        assertThat(d.lines().filter(l -> l.startsWith("FROM ")).toList()).allMatch(l -> l.startsWith("FROM ${JRE_IMAGE}"));
    }

    @Test
    void the_image_bakes_in_no_configuration_and_no_secret() throws IOException {
        String d = Files.readString(DOCKERFILE);
        for (String line : d.lines().filter(l -> l.startsWith("ENV ") || l.startsWith("ARG ")).toList()) {
            assertThat(line).as(line).doesNotContainPattern(Pattern.compile("(?i)(MONGODB|TOKEN|KEY|SECRET|PASSWORD|REDIS|OIDC)"));
        }
        assertThat(d).doesNotContain("application.yml").doesNotContain(".env").doesNotContain("ADD ");
        assertThat(Files.readAllLines(IGNORE).stream().filter(l -> !l.isBlank() && !l.startsWith("#")).toList())
                .containsExactly("*", "!target/catalog-service-*.jar");
    }

    @Test
    void shutdown_is_graceful_and_bounded() throws IOException {
        List<PropertySource<?>> yml = new YamlPropertySourceLoader()
                .load("application", new FileSystemResource("src/main/resources/application.yml"));
        PropertySource<?> p = yml.get(0);
        assertThat(String.valueOf(p.getProperty("server.shutdown"))).isEqualTo("graceful");
        String grace = String.valueOf(p.getProperty("spring.lifecycle.timeout-per-shutdown-phase"));
        assertThat(grace).isEqualTo("${TAZZZO_SHUTDOWN_GRACE:25s}");
        Duration fallback = Duration.parse("PT" + grace.replaceAll(".*:(\\d+)s}$", "$1") + "S");
        assertThat(fallback).as("below the usual 30 s orchestrator kill timeout").isLessThan(Duration.ofSeconds(30));
    }
}
