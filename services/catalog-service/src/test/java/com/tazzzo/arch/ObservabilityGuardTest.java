package com.tazzzo.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static guard over the observability sources added with the application-level metrics (the same spirit as
 * {@link LogSafetyGuardTest}, which already scans EVERY production log call for credential-like arguments): a meter tag value
 * in these classes may only be a string literal or the lower-cased name of an enum constant (or a closed-set helper); a log call
 * may only carry an exception CLASS name, never a message, id or URI. So nothing a request, a file or an error can contain
 * becomes a tag or a log field here.
 */
class ObservabilityGuardTest {

    static final Path MAIN = Path.of("src/main/java/com/tazzzo");
    static final List<String> FILES = List.of(
            "bulkimport/ImportMetrics.java",
            "bulkimport/jobs/ImportJobGauges.java",
            "media/MediaMetrics.java",
            "commerce/read/ProjectionMetrics.java",
            "commerce/read/ProjectionQueueGauges.java",
            "catalog/api/AdminHttpMetrics.java",
            "catalog/api/AdminHttpMetricsFilter.java",
            "common/metrics/SnapshotCache.java");

    static final Pattern TAG_START = Pattern.compile("\\.tag\\(\\s*\"([a-z_]+)\"\\s*,");
    static final Pattern SAFE_VALUE = Pattern.compile(
            "\"[a-z_]+\"|word\\([a-z]+\\)|[a-z]+\\.name\\(\\)\\.toLowerCase\\((java\\.util\\.)?Locale\\.ROOT\\)"
                    + "|routeLabel\\(request\\.getAttribute\\(HandlerMapping\\.BEST_MATCHING_PATTERN_ATTRIBUTE\\)\\)|statusClass\\(status\\)");
    static final Pattern LOG_CALL = Pattern.compile("\\blog\\.(trace|debug|info|warn|error)\\s*\\((.*?)\\);", Pattern.DOTALL);

    @Test
    void tag_values_are_literals_or_enum_words_never_request_derived_text() throws IOException {
        List<String> bad = new ArrayList<>();
        int tags = 0;
        for (String f : FILES) {
            String src = Files.readString(MAIN.resolve(f));
            Matcher m = TAG_START.matcher(src);
            while (m.find()) {
                tags++;
                int depth = 1;
                int i = m.end();
                while (depth > 0) {
                    char c = src.charAt(i++);
                    if (c == '(') depth++;
                    if (c == ')') depth--;
                }
                String value = src.substring(m.end(), i - 1).replaceAll("\\s+", " ").trim();
                if (!SAFE_VALUE.matcher(value).matches()) bad.add(f + ": .tag(\"" + m.group(1) + "\", " + value + ")");
            }
        }
        assertThat(tags).as("tag call sites scanned").isGreaterThan(10);
        assertThat(bad).isEmpty();
    }

    @Test
    void log_calls_carry_only_the_exception_class_name() throws IOException {
        List<String> bad = new ArrayList<>();
        for (String f : FILES) {
            Matcher m = LOG_CALL.matcher(Files.readString(MAIN.resolve(f)));
            while (m.find()) {
                String args = m.group(2).replaceAll("\\s+", " ");
                if (!args.endsWith("e.getClass().getSimpleName()")) bad.add(f + ": " + args);
            }
        }
        assertThat(bad).isEmpty();
    }

    @Test
    void the_guard_itself_rejects_what_it_must() {
        for (String unsafe : new String[]{"request.getRequestURI()", "job.id()", "e.getMessage()", "req.getHeader(\"x\")", "skuId", "routeLabel(request.getRequestURI())", "uri.toLowerCase(Locale.ROOT)"}) {
            assertThat(SAFE_VALUE.matcher(unsafe).matches()).as(unsafe).isFalse();
        }
        for (String safe : new String[]{"\"internal\"", "word(phase)", "routeLabel(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))", "statusClass(status)"}) {
            assertThat(SAFE_VALUE.matcher(safe).matches()).as(safe).isTrue();
        }
    }
}
