package com.tazzzo.admin.audit;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Syntax-only validation of the RAW query string of an audit-read request, run BEFORE the servlet parameter map is trusted.
 *
 * <p><b>Why.</b> The container binds parameters before the controller runs, and it silently DROPS a parameter whose value it
 * cannot decode (e.g. {@code actorType=%zz}) and any empty-named component ({@code =x}). Left alone, a dropped
 * {@code actorType} filter becomes an unfiltered query and a dropped {@code cursor} restarts the traversal, both answered 200.
 * This class makes those requests fail closed instead.
 *
 * <p><b>What it checks</b> (each failure is {@link AuditQueryRejected}, a 400 with one fixed message):
 * <ol>
 *   <li>every {@code &}-separated component is non-empty (rejects {@code &&}, {@code &foo}, {@code foo&});</li>
 *   <li>no component has an empty name ({@code =foo});</li>
 *   <li>every {@code %} introduces exactly two hex digits ({@code %}, {@code %1}, {@code %GG}, {@code %zz} are rejected);</li>
 *   <li>the percent-decoded bytes of a component are well-formed UTF-8 (checked, then discarded);</li>
 *   <li>the container bound exactly as many parameter values as the raw string has components, so a parameter dropped for
 *       ANY other container reason is also refused rather than ignored.</li>
 * </ol>
 *
 * <p><b>What it does not do.</b> It never returns or stores a decoded value, so nothing can be decoded twice ({@code %2524}
 * stays the container's single decode to {@code %24}); it repairs and normalises nothing; and it does not allowlist names or
 * validate values: {@link AuditEventQuery#parse} remains the sole authority for that. An absent or empty query string is
 * valid (it carries no components).
 */
public final class RawQuerySyntax {

    static final String MALFORMED = "query string is malformed";

    private RawQuerySyntax() {
    }

    /**
     * Binds the container's parameters for the audit read. Tomcat 11 (Spring Boot 4) REFUSES an undecodable parameter by
     * throwing {@link IllegalStateException} from {@code getParameterMap()} instead of silently dropping it (Tomcat 10 did).
     * That is the same malformed-query refusal this class exists to produce, so it is mapped to the same 400 and fixed
     * message rather than surfacing as a generic state conflict.
     */
    public static Map<String, String[]> bind(Supplier<Map<String, String[]>> parameterMap) {
        try {
            return parameterMap.get();
        } catch (IllegalStateException e) {
            throw new AuditQueryRejected(MALFORMED);
        }
    }

    public static void requireWellFormed(String rawQuery, Map<String, String[]> boundParameters) {
        int components = 0;
        if (rawQuery != null && !rawQuery.isEmpty()) {
            for (String component : rawQuery.split("&", -1)) {
                if (component.isEmpty() || component.charAt(0) == '=') {
                    throw new AuditQueryRejected(MALFORMED);
                }
                requireWellFormedEncoding(component);
                components++;
            }
        }
        int bound = 0;
        for (String[] values : boundParameters.values()) {
            bound += values == null ? 0 : values.length;
        }
        if (bound != components) {
            throw new AuditQueryRejected(MALFORMED);
        }
    }

    /** ASCII hex digit value, or -1 (deliberately not {@link Character#digit}, which also accepts non-ASCII digits). */
    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /** Validates {@code %XX} syntax and UTF-8 well-formedness of one raw component. The decoded bytes are not kept. */
    private static void requireWellFormedEncoding(String component) {
        byte[] decoded = new byte[component.length()];
        int n = 0;
        for (int i = 0; i < component.length(); i++) {
            char c = component.charAt(i);
            if (c == '%') {
                if (i + 2 >= component.length()) {
                    throw new AuditQueryRejected(MALFORMED);
                }
                int hi = hex(component.charAt(i + 1));
                int lo = hex(component.charAt(i + 2));
                if (hi < 0 || lo < 0) {
                    throw new AuditQueryRejected(MALFORMED);
                }
                decoded[n++] = (byte) ((hi << 4) | lo);
                i += 2;
            } else if (c < 0x80) {
                decoded[n++] = (byte) c;
            } else {
                // a raw non-ASCII character is not valid query syntax (it must be percent-encoded)
                throw new AuditQueryRejected(MALFORMED);
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decoded, 0, n));
        } catch (CharacterCodingException e) {
            throw new AuditQueryRejected(MALFORMED);
        }
    }
}
