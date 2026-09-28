package com.tazzzo.auth;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-11D structural guard: {@code ClientSession.withTransaction} may run a callback several times,
 * so no Auth code may smuggle a transaction result out through an external holder (a one-element
 * array / atomic captured by the lambda). Results must be returned through {@code Tx.call}.
 */
class AuthTxHolderGuardTest {

    private static final Pattern ARRAY_HOLDER = Pattern.compile("\\b\\w+\\[\\]\\s+\\w+\\s*=\\s*new\\s+\\w+\\[\\s*\\d\\s*\\]");
    private static final Pattern ATOMIC_HOLDER = Pattern.compile("\\bAtomic(Reference|Long|Integer|Boolean)\\b");
    private static final Pattern TX_RUN = Pattern.compile("\\btx\\.run\\(");

    @Test void auth_main_source_has_no_transaction_result_holder_and_no_result_bearing_tx_run() throws IOException {
        Path dir = Path.of("src/main/java/com/tazzzo/auth");
        assertThat(Files.isDirectory(dir)).as("missing " + dir.toAbsolutePath()).isTrue();
        List<String> offenders;
        try (Stream<Path> files = Files.walk(dir)) {
            offenders = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                String src = read(p);
                return ARRAY_HOLDER.matcher(src).find() || ATOMIC_HOLDER.matcher(src).find()
                        || TX_RUN.matcher(stripComments(src)).find();
            }).map(Path::toString).toList();
        }
        assertThat(offenders).as("Auth transactions must return results via Tx.call, never external holders")
                .isEmpty();
    }

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
