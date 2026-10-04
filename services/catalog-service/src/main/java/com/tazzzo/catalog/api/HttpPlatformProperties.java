package com.tazzzo.catalog.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Platform HTTP baseline: request-body size limit and the explicit CORS policy. Neither has a permissive default:
 * the body limit is always on (64 KiB unless configured; the largest legitimate request body in this API is a few KiB),
 * and CORS is OFF unless an exact origin allowlist is configured ({@code TAZZZO_HTTP_CORS_ALLOWED_ORIGINS}).
 *
 * <p>Origin rules: exact scheme+host[+port] only; {@code https} required except for {@code http://localhost} and
 * {@code http://127.0.0.1} (local development); no wildcard, no path, no userinfo, no query.
 */
@ConfigurationProperties(prefix = "tazzzo.http")
public class HttpPlatformProperties {

    public static final long MIN_BODY_BYTES = 1024;
    public static final long MAX_BODY_BYTES = 16L * 1024 * 1024;

    private long maxRequestBodyBytes = 65_536;
    private Cors cors = new Cors();

    public long getMaxRequestBodyBytes() {
        return maxRequestBodyBytes;
    }

    public void setMaxRequestBodyBytes(long maxRequestBodyBytes) {
        this.maxRequestBodyBytes = maxRequestBodyBytes;
    }

    public Cors getCors() {
        return cors;
    }

    public void setCors(Cors cors) {
        this.cors = cors;
    }

    /** Fails startup rather than running with a limit or an origin list that cannot be what the operator meant. */
    public void validate() {
        if (maxRequestBodyBytes < MIN_BODY_BYTES || maxRequestBodyBytes > MAX_BODY_BYTES) {
            throw new IllegalStateException("tazzzo.http.max-request-body-bytes must be between " + MIN_BODY_BYTES
                    + " and " + MAX_BODY_BYTES + ", was " + maxRequestBodyBytes);
        }
        cors.validate();
    }

    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>();
        private long maxAgeSeconds = 600;

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        public long getMaxAgeSeconds() {
            return maxAgeSeconds;
        }

        public void setMaxAgeSeconds(long maxAgeSeconds) {
            this.maxAgeSeconds = maxAgeSeconds;
        }

        public boolean enabled() {
            return normalizedOrigins().size() > 0;
        }

        /** The exact origins, lower-cased, blanks dropped. */
        public List<String> normalizedOrigins() {
            List<String> out = new ArrayList<>();
            for (String o : allowedOrigins == null ? List.<String>of() : allowedOrigins) {
                if (o != null && !o.isBlank()) {
                    out.add(o.trim().toLowerCase(Locale.ROOT));
                }
            }
            return List.copyOf(out);
        }

        public void validate() {
            if (maxAgeSeconds < 0 || maxAgeSeconds > 86_400) {
                throw new IllegalStateException("tazzzo.http.cors.max-age-seconds must be between 0 and 86400");
            }
            for (String origin : normalizedOrigins()) {
                requireExactOrigin(origin);
            }
        }

        static void requireExactOrigin(String origin) {
            String why = originProblem(origin);
            if (why != null) {
                throw new IllegalStateException("tazzzo.http.cors.allowed-origins entry is not an exact origin (" + why + ")");
            }
        }

        /** {@code null} when {@code origin} is an acceptable exact origin, otherwise the reason it is not. */
        static String originProblem(String origin) {
            if (origin.contains("*")) {
                return "wildcards are not allowed";
            }
            URI uri;
            try {
                uri = new URI(origin);
            } catch (Exception e) {
                return "not a URI";
            }
            if (uri.getScheme() == null || uri.getHost() == null || uri.getHost().isBlank()) {
                return "scheme and host are required";
            }
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
                return "only scheme, host and port are allowed";
            }
            boolean local = uri.getHost().equals("localhost") || uri.getHost().equals("127.0.0.1");
            if (!uri.getScheme().equals("https") && !(uri.getScheme().equals("http") && local)) {
                return "https is required (http only for localhost)";
            }
            return null;
        }
    }
}
