package com.tazzzo.catalog.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Recognises a trusted server-side caller (the storefront) on a public read, so it is admitted against its
 * OWN bucket ({@code caller:<name>}) instead of sharing its egress IP's bucket with everyone else.
 *
 * <pre>
 *   X-Tazzzo-Caller: storefront              the configured name (not secret)
 *   X-Tazzzo-Caller-Secret: &lt;shared secret&gt;   compared in constant time
 *
 *   both present, name configured, secret matches  -> trusted (caller bucket only)
 *   neither present                                -> untrusted, silently (today's behaviour)
 *   anything else (one header, unknown name,
 *   wrong secret)                                  -> untrusted, exactly as today; WARN at most once
 *                                                     a minute per configured name, never the secret
 * </pre>
 *
 * <p><b>Never an authentication failure.</b> A bad credential is simply untrusted: the request falls back to
 * the IP/installation buckets and is never refused for it. This is a rate-limit dimension, not authority —
 * it grants no catalogue capability, and nothing about end users is read from the caller (no change to
 * {@code X-Forwarded-For} trust).
 *
 * <p><b>Secrets.</b> Only SHA-256 digests are kept, and they are compared with {@link MessageDigest#isEqual},
 * whose running time does not depend on where the inputs differ; equal-length digests also hide the secret's
 * length. An unknown name is compared against a dummy digest so it costs the same. A presented name is only
 * ever logged when it is a configured name; anything else is logged as {@code unknown}, so request data
 * never reaches the log.
 */
public final class TrustedCallerResolver {

    private static final Logger log = LoggerFactory.getLogger(TrustedCallerResolver.class);

    public static final String CALLER_HEADER = "X-Tazzzo-Caller";
    public static final String SECRET_HEADER = "X-Tazzzo-Caller-Secret";
    /**
     * Lowercase letters and underscores, at most 20: a safe bucket-key suffix and a bounded metric tag. Not
     * {@value #UNKNOWN}, which is the log label for every unconfigured name.
     */
    static final Pattern NAME = Pattern.compile("(?!unknown$)[a-z][a-z_]{0,19}");
    static final int MIN_SECRET_LENGTH = 32;
    static final int MAX_SECRET_LENGTH = 256;
    static final Duration WARN_INTERVAL = Duration.ofMinutes(1);
    static final String UNKNOWN = "unknown";

    enum Rejection { MISSING_NAME, MISSING_SECRET, UNKNOWN_NAME, WRONG_SECRET }

    private final Map<String, byte[]> digests;
    private final Map<String, WarnWindow> warnWindows;
    private final byte[] dummyDigest;
    private final LongSupplier nanoClock;

    public TrustedCallerResolver(List<TrustedCallerProperties.Caller> callers) {
        this(callers, System::nanoTime);
    }

    TrustedCallerResolver(List<TrustedCallerProperties.Caller> callers, LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        Map<String, byte[]> byName = new LinkedHashMap<>();
        List<TrustedCallerProperties.Caller> entries = callers == null ? List.of() : callers;
        for (int i = 0; i < entries.size(); i++) {
            TrustedCallerProperties.Caller caller = entries.get(i);
            String where = "tazzzo.consumer.trusted-callers[" + i + "]";
            String name = caller == null ? null : caller.getName();
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalStateException(where + ".name must match " + NAME.pattern());
            }
            if (byName.containsKey(name)) {
                throw new IllegalStateException(where + ".name '" + name + "' is configured twice");
            }
            // NEITHER the value NOR any fragment of it may appear in these messages.
            String secret = caller.getSecret();
            if (secret == null || secret.length() < MIN_SECRET_LENGTH || secret.length() > MAX_SECRET_LENGTH
                    || !printableAscii(secret)) {
                throw new IllegalStateException(where + ".secret for '" + name + "' must be "
                        + MIN_SECRET_LENGTH + ".." + MAX_SECRET_LENGTH
                        + " printable ASCII characters without spaces (value withheld)");
            }
            byName.put(name, sha256(secret));
        }
        this.digests = Map.copyOf(byName);
        this.dummyDigest = sha256("tazzzo-trusted-caller-no-such-name");
        // The window map is FIXED at construction: request data can never add a key to it.
        Map<String, WarnWindow> windows = new LinkedHashMap<>();
        long now = nanoClock.getAsLong();
        for (String name : byName.keySet()) {
            windows.put(name, new WarnWindow(now));
        }
        windows.put(UNKNOWN, new WarnWindow(now));
        this.warnWindows = Map.copyOf(windows);
        if (!digests.isEmpty()) {
            log.info("trusted consumer callers configured: {}", new ArrayList<>(byName.keySet()));
        }
    }

    public boolean hasCallers() {
        return !digests.isEmpty();
    }

    /**
     * @param callerHeader raw {@value #CALLER_HEADER}, or null
     * @param secretHeader raw {@value #SECRET_HEADER}, or null
     * @return the configured caller name when, and only when, the credential is valid
     */
    public Optional<String> resolve(String callerHeader, String secretHeader) {
        boolean hasName = callerHeader != null && !callerHeader.isBlank();
        boolean hasSecret = secretHeader != null && !secretHeader.isEmpty();
        if (!hasName && !hasSecret) {
            return Optional.empty();                       // the ordinary anonymous request
        }
        String name = hasName ? callerHeader.trim() : null;
        byte[] expected = name == null ? null : digests.get(name);
        String logKey = expected == null ? UNKNOWN : name;
        if (!hasName) {
            warn(logKey, Rejection.MISSING_NAME);
            return Optional.empty();
        }
        if (!hasSecret) {
            warn(logKey, Rejection.MISSING_SECRET);
            return Optional.empty();
        }
        boolean match = MessageDigest.isEqual(sha256(secretHeader), expected == null ? dummyDigest : expected);
        if (expected == null) {
            warn(logKey, Rejection.UNKNOWN_NAME);
            return Optional.empty();
        }
        if (!match) {
            warn(logKey, Rejection.WRONG_SECRET);
            return Optional.empty();
        }
        return Optional.of(name);
    }

    /** At most one WARN per {@link #WARN_INTERVAL} per key; the rest are counted and reported with the next. */
    private void warn(String key, Rejection reason) {
        WarnWindow window = warnWindows.get(key);
        long now = nanoClock.getAsLong();
        long next = window.nextAllowed.get();
        if (now - next >= 0 && window.nextAllowed.compareAndSet(next, now + WARN_INTERVAL.toNanos())) {
            long suppressed = window.suppressed.getAndSet(0);
            log.warn("trusted_caller_rejected caller={} reason={} suppressed_since_last={} "
                    + "(admitted as an ordinary client)", key, reason.name().toLowerCase(Locale.ROOT), suppressed);
        } else {
            window.suppressed.incrementAndGet();
        }
    }

    private static boolean printableAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);   // mandated by every JRE
        }
    }

    private static final class WarnWindow {
        final AtomicLong nextAllowed;
        final AtomicLong suppressed = new AtomicLong();

        WarnWindow(long now) {
            this.nextAllowed = new AtomicLong(now);
        }
    }
}
