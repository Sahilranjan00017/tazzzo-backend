package com.tazzzo.auth.otp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-11B hardening §5 — production OTP lifecycle time decisions MUST use the injected {@link
 * java.time.Clock} or an explicit {@link java.time.Instant} supplied by the caller, never a direct
 * wall-clock read. This is enforced structurally here rather than trusted to code review, because a
 * single reintroduced {@code Instant.now()} would silently make the lifecycle nondeterministic and
 * untestable again.
 */
class OtpPackageConventionsTest {

    @Test void no_instant_now_anywhere_in_the_otp_main_source_tree() throws IOException {
        Path dir = Path.of("src/main/java/com/tazzzo/auth/otp");
        assertThat(Files.isDirectory(dir))
                .as("expected OTP main source directory not found at " + dir.toAbsolutePath())
                .isTrue();
        List<String> offenders;
        try (Stream<Path> files = Files.walk(dir)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(OtpPackageConventionsTest::containsWallClockRead)
                    .map(Path::toString)
                    .toList();
        }
        assertThat(offenders)
                .as("Instant.now() is forbidden in OTP business/repository code — use the injected Clock")
                .isEmpty();
    }

    private static boolean containsWallClockRead(Path file) {
        try {
            return Files.readString(file).contains("Instant.now()");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
