package com.tazzzo.catalog.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
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
 * length. <b>Rotation:</b> each caller has a current secret and an optional {@code previous-secret}; the
 * presented secret is compared against BOTH slots every time (an absent previous slot holds random bytes and
 * is masked out) and the results are combined without short-circuiting, so the work is the same whichever
 * secret matches, or none. An unknown name is compared against two dummy digests so it costs the same. A
 * presented name is only ever logged when it is a configured name; anything else is logged as
 * {@code unknown}, so request data never reaches the log.
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

    /** A caller's two comparison slots; {@code previous} is random bytes, masked out, when none is configured. */
    private record Credential(byte[] current, byte[] previous, boolean hasPrevious) { }

    private final Map<String, Credential> credentials;
    private final Map<String, WarnWindow> warnWindows;
    private final Credential dummy;
    private final LongSupplier nanoClock;

    public TrustedCallerResolver(List<TrustedCallerProperties.Caller> callers) {
        this(callers, System::nanoTime);
    }

    TrustedCallerResolver(List<TrustedCallerProperties.Caller> callers, LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        Map<String, Credential> byName = new LinkedHashMap<>();
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
            // NEITHER value NOR any fragment of one may appear in these messages.
            byte[] current = validSecretDigest(caller.getSecret(), where + ".secret", name);
            String previousSecret = caller.getPreviousSecret();
            boolean hasPrevious = previousSecret != null && !previousSecret.isEmpty();
            byte[] previous = hasPrevious
                    ? validSecretDigest(previousSecret, where + ".previous-secret", name) : randomBytes();
            if (hasPrevious && MessageDigest.isEqual(current, previous)) {
                throw new IllegalStateException(where + ".previous-secret for '" + name
                        + "' equals its secret (value withheld): remove it once rotation is complete");
            }
            byName.put(name, new Credential(current, previous, hasPrevious));
        }
        this.credentials = Map.copyOf(byName);
        this.dummy = new Credential(randomBytes(), randomBytes(), true);
        // The window map is FIXED at construction: request data can never add a key to it.
        Map<String, WarnWindow> windows = new LinkedHashMap<>();
        long now = nanoClock.getAsLong();
        for (String name : byName.keySet()) {
            windows.put(name, new WarnWindow(now));
        }
        windows.put(UNKNOWN, new WarnWindow(now));
        this.warnWindows = Map.copyOf(windows);
        if (!credentials.isEmpty()) {
            log.info("trusted consumer callers configured: {}", new ArrayList<>(byName.keySet()));
        }
    }

    public boolean hasCallers() {
        return !credentials.isEmpty();
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
        Credential expected = name == null ? null : credentials.get(name);
        String logKey = expected == null ? UNKNOWN : name;
        if (!hasName) {
            warn(logKey, Rejection.MISSING_NAME);
            return Optional.empty();
        }
        if (!hasSecret) {
            warn(logKey, Rejection.MISSING_SECRET);
            return Optional.empty();
        }
        Credential against = expected == null ? dummy : expected;
        byte[] presented = sha256(secretHeader);
        // Both slots, every time, combined with the NON-short-circuit operators: constant work whichever matches.
        boolean matchesCurrent = MessageDigest.isEqual(presented, against.current());
        boolean matchesPrevious = MessageDigest.isEqual(presented, against.previous());
        boolean match = matchesCurrent | (matchesPrevious & against.hasPrevious());
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

    private static byte[] validSecretDigest(String secret, String property, String name) {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH || secret.length() > MAX_SECRET_LENGTH
                || !printableAscii(secret)) {
            throw new IllegalStateException(property + " for '" + name + "' must be "
                    + MIN_SECRET_LENGTH + ".." + MAX_SECRET_LENGTH
                    + " printable ASCII characters without spaces (value withheld)");
        }
        return sha256(secret);
    }

    /** Has no known preimage, so a comparison slot holding it can never be matched by a presented secret. */
    private static byte[] randomBytes() {
        byte[] out = new byte[32];
        new SecureRandom().nextBytes(out);
        return out;
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
