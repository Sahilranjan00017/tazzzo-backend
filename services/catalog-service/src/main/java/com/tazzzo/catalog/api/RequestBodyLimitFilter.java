package com.tazzzo.catalog.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Application-level request-body size limit (platform baseline). Every request on every surface is bounded by
 * {@code tazzzo.http.max-request-body-bytes}, independently of whatever an edge (ALB, WAF) may or may not enforce, and
 * the decision is made HERE, before authentication and before any controller reads a byte:
 * <ul>
 *   <li>a declared {@code Content-Length} above the limit is refused with 413 without reading the body (the container
 *       enforces a declared length that is within the limit);</li>
 *   <li>a body with no declared length (chunked) is read into a buffer bounded by the limit; the first byte past it is
 *       a 413, otherwise the buffered body is what the application reads. Bounded memory per request, by construction.</li>
 * </ul>
 * The 413 carries the surface's own error envelope and {@code Connection: close}, so a client that keeps sending is
 * cut off rather than drained ({@code server.tomcat.max-swallow-size} is set to the same limit).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1) // right after the request id; before CORS and both auth filters
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    public static final String CODE = "PAYLOAD_TOO_LARGE";
    public static final String MESSAGE = "request body too large";

    private final long limitBytes;
    private final ObjectMapper mapper;

    public RequestBodyLimitFilter(HttpPlatformProperties properties, ObjectMapper mapper) {
        properties.validate();
        this.limitBytes = properties.getMaxRequestBodyBytes();
        this.mapper = mapper;
    }

    public long limitBytes() {
        return limitBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long declared = req.getContentLengthLong();
        if (declared > limitBytes) {
            reject(req, res);
            return;
        }
        if (declared >= 0 || !mayCarryBody(req)) {
            chain.doFilter(req, res);
            return;
        }
        byte[] body = readBounded(req.getInputStream(), limitBytes);
        if (body == null) {
            reject(req, res);
            return;
        }
        chain.doFilter(new BufferedBodyRequest(req, body), res);
    }

    private static boolean mayCarryBody(HttpServletRequest req) {
        String m = req.getMethod();
        return !("GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m) || "TRACE".equals(m));
    }

    /** Up to {@code limit} bytes, or {@code null} as soon as the stream holds more than that. */
    static byte[] readBounded(InputStream in, long limit) throws IOException {
        byte[] buf = new byte[(int) Math.min(limit + 1, Integer.MAX_VALUE - 8)];
        int total = 0;
        int n;
        while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) {
            total += n;
        }
        if (total > limit) {
            return null;
        }
        return java.util.Arrays.copyOf(buf, total);
    }

    private void reject(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        res.setContentType("application/json");
        res.setHeader("Connection", "close");
        String requestId = String.valueOf(req.getAttribute(RequestIdFilter.REQUEST_ID));
        SurfaceClassifier.Surface surface = SurfaceClassifier.classify(req.getRequestURI());
        Object body = surface == SurfaceClassifier.Surface.INTERNAL
                ? Map.of("error", Map.of("code", CODE, "message", MESSAGE, "request_id", requestId))
                : Map.of("code", CODE, "message", MESSAGE, "request_id", requestId);
        mapper.writeValue(res.getWriter(), body);
    }

    /** The already-bounded body, served to the application as the request's input. */
    static final class BufferedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private ServletInputStream stream;
        private BufferedReader reader;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            if (stream == null) {
                stream = new ByteArrayServletInputStream(body);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String enc = getCharacterEncoding();
                reader = new BufferedReader(new InputStreamReader(getInputStream(),
                        enc == null ? StandardCharsets.UTF_8.name() : enc));
            }
            return reader;
        }
    }

    static final class ByteArrayServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;

        ByteArrayServletInputStream(byte[] body) {
            this.delegate = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return delegate.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException("buffered body: no async read");
        }
    }
}
