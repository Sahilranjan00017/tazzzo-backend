package com.tazzzo.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static guard: no log statement in production code passes a value whose NAME says it is a credential or contact datum
 * (token, authorization header, password, secret, phone, email, OTP code, session id). The message text may mention such
 * words; only the ARGUMENTS are checked. A false positive is resolved by renaming (e.g. a digest) or by an entry in
 * {@link #REVIEWED} with the reason it is safe: never by weakening the pattern.
 */
class LogSafetyGuardTest {

    static final Path MAIN = Path.of("src/main/java");
    static final Pattern CALL = Pattern.compile("\\blog\\.(trace|debug|info|warn|error)\\s*\\((.*?)\\);", Pattern.DOTALL);
    static final Pattern FORBIDDEN = Pattern.compile(
            "(?i)(token|authorization|bearer|password|secret|apikey|api_key|phone|msisdn|email|otp(?!Challenge)|sessionid|session\\(\\)|refresh)");

    /** "File.java: argument" entries proven safe, each with its reason. */
    static final Set<String> REVIEWED = Set.of(
            // dev-only provider (refused outside unset/local/test/dev, OtpLoggingProviderGuardTest); "+91******1234"
            "LoggingOtpDeliveryProvider.java: phone.masked()");

    static List<String> arguments(String call) {
        // drop the leading string literal (the message), keep the argument expressions
        String rest = call.replaceFirst("^\\s*\"(?:[^\"\\\\]|\\\\.)*\"\\s*(?:\\+\\s*\"(?:[^\"\\\\]|\\\\.)*\"\\s*)*", "");
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : rest.toCharArray()) {
            if (c == '(' || c == '{' || c == '[') depth++;
            if (c == ')' || c == '}' || c == ']') depth--;
            if (c == ',' && depth == 0) {
                if (!cur.toString().isBlank()) out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.toString().isBlank()) out.add(cur.toString().trim());
        // string literals inside arguments are message text, not values
        return out.stream().map(a -> a.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"")).toList();
    }

    @Test
    void no_log_statement_passes_a_credential_or_contact_value() throws IOException {
        List<String> findings = new ArrayList<>();
        int calls = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                Matcher m = CALL.matcher(src);
                while (m.find()) {
                    calls++;
                    for (String arg : arguments(m.group(2))) {
                        if (FORBIDDEN.matcher(arg).find()) {
                            String key = f.getFileName() + ": " + arg.replaceAll("\\s+", " ");
                            if (!REVIEWED.contains(key)) findings.add(key);
                        }
                    }
                }
            }
        }
        assertThat(calls).as("log calls scanned").isGreaterThan(100);
        assertThat(findings).as("log arguments that look like credentials or contact data").isEmpty();
    }

    @Test
    void the_guard_itself_sees_what_it_must() {
        assertThat(arguments("\"otp sent to {}\", phone")).containsExactly("phone");
        assertThat(arguments("\"x {} {}\", a, f(b, c)")).containsExactly("a", "f(b, c)");
        assertThat(FORBIDDEN.matcher("request.getHeader(\"\")").find()).isFalse();
        for (String bad : new String[]{"accessToken", "req.getHeader(AUTHORIZATION)", "cmd.phone()", "user.email()",
                "refreshToken", "sessionId", "otpCode", "secretKey", "password"}) {
            assertThat(FORBIDDEN.matcher(bad).find()).as(bad).isTrue();
        }
        for (String ok : new String[]{"requestId", "e.getClass().getSimpleName()", "reason", "otpChallengeId", "sku"}) {
            assertThat(FORBIDDEN.matcher(ok).find()).as(ok).isFalse();
        }
    }
}
