package com.tazzzo.catalog.datastore;

import com.mongodb.ConnectionString;
import com.mongodb.ReadConcernLevel;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The explicit MongoDB connection contract (DB-4, risk R7). Until now every concern, preference, retry, pool and
 * timeout setting was implicit (driver defaults plus whatever the deployed URI happened to carry), so deployment
 * gates 5 and 6 could not be verified from the repository. For {@code staging} and {@code production} each of
 * these must be STATED in the connection string, and the application refuses to start if it is not.
 *
 * <p>Pure: it parses the configured URI with the driver's own {@link ConnectionString} and never connects. Its
 * messages name options only; nothing in a {@link Violation} can contain a host, a user or a password.
 *
 * <p>Required (every one must be present AND have the stated value):
 * <ul>
 *   <li>TLS ({@code tls=true}, or {@code mongodb+srv://} without {@code tls=false}); certificate and hostname
 *       validation must not be relaxed ({@code tlsInsecure}, {@code tlsAllowInvalidCertificates},
 *       {@code tlsAllowInvalidHostnames});</li>
 *   <li>credentials in the URI (the runtime and the migration job each use their own identity);</li>
 *   <li>a replica-set topology ({@code replicaSet=...} or SRV) and no {@code directConnection=true}: every write
 *       in this backend uses a multi-document transaction;</li>
 *   <li>no loopback host (this is what the localhost default of {@code MONGODB_URI} looks like when it was never set);</li>
 *   <li>{@code retryWrites=true} and {@code retryReads=true};</li>
 *   <li>{@code w=majority}, {@code readConcernLevel=majority}, {@code readPreference=primary};</li>
 *   <li>{@code connectTimeoutMS} in [{@value #MIN_TIMEOUT_MS}, {@value #MAX_CONNECT_TIMEOUT_MS}] and
 *       {@code serverSelectionTimeoutMS} in [{@value #MIN_TIMEOUT_MS}, {@value #MAX_SERVER_SELECTION_TIMEOUT_MS}], so
 *       an unreachable datastore fails startup in bounded time;</li>
 *   <li>{@code maxPoolSize} in [1, {@value #MAX_POOL_SIZE}] (and {@code minPoolSize} not above it).</li>
 * </ul>
 * Optional, bounded when present: {@code socketTimeoutMS} must be 0 (off) or at least
 * {@value #MIN_SOCKET_TIMEOUT_MS} ms (a tight socket timeout aborts an in-flight transaction with an ambiguous
 * outcome); {@code waitQueueTimeoutMS} must be at most {@value #MAX_WAIT_QUEUE_MS} ms.
 *
 * <p>Also refused when present (they weaken what the required settings promise): {@code journal=false} (an explicit
 * {@code j:false} lets a {@code w=majority} write be acknowledged before it reaches the on-disk journal),
 * {@code wtimeoutMS} between 1 and {@value #MIN_WTIMEOUT_MS} (majority acknowledgement becomes effectively unusable;
 * 0 = no limit stays allowed), a blank {@code replicaSet}, certificate-revocation checking switched off
 * ({@code tlsDisableOCSPEndpointCheck}, {@code tlsDisableCertificateRevocationCheck}), and any {@code proxy*} option
 * (a proxy changes the network route, so the target can no longer be classified from the host list).
 *
 * <p><b>PROPOSED — OWNER RATIFICATION REQUIRED:</b> every NUMERIC limit in this class (the timeout ceilings, the socket
 * and wait-queue bounds, the {@code maxPoolSize} cap, and the {@code wtimeoutMS} floor) was chosen by the DB-4
 * implementation as a conservative starting point. None has been approved by the owner; they are validation
 * thresholds, not ratified requirements, and may change (for example the pool cap) once production sizing is known.
 * The non-numeric rules (TLS, credentials, replica set, retries, majority concerns, primary reads) are the ratified
 * intent of risk R7.
 */
public final class ConnectionContract {

    // PROPOSED — OWNER RATIFICATION REQUIRED for every numeric limit below (see the class documentation).
    public static final int MIN_TIMEOUT_MS = 1_000;
    public static final int MAX_CONNECT_TIMEOUT_MS = 15_000;
    public static final int MAX_SERVER_SELECTION_TIMEOUT_MS = 30_000;
    public static final int MIN_SOCKET_TIMEOUT_MS = 60_000;
    public static final int MAX_WAIT_QUEUE_MS = 30_000;
    public static final int MAX_POOL_SIZE = 100;
    public static final int MIN_WTIMEOUT_MS = 1_000;

    private ConnectionContract() {
    }

    /** All findings for {@code uri}; empty means the URI satisfies the contract. */
    public static List<Violation> evaluate(String uri) {
        List<Violation> v = new ArrayList<>();
        if (uri == null || uri.isBlank()) {
            v.add(new Violation("URI_MISSING", "no MongoDB connection string is configured (MONGODB_URI)"));
            return v;
        }
        ConnectionString cs;
        try {
            cs = new ConnectionString(uri);
        } catch (RuntimeException e) {
            v.add(new Violation("URI_INVALID", "the MongoDB connection string is not valid"));
            return v;
        }

        boolean tls = Boolean.TRUE.equals(cs.getSslEnabled()) || (cs.isSrvProtocol() && !Boolean.FALSE.equals(cs.getSslEnabled()));
        if (!tls) {
            v.add(new Violation("TLS_REQUIRED", "TLS must be enabled (tls=true, or an SRV connection string)"));
        }
        if (Boolean.TRUE.equals(cs.getSslInvalidHostnameAllowed()) || rawOptionIsTrue(uri, "tlsallowinvalidcertificates")
                || rawOptionIsTrue(uri, "tlsinsecure") || rawOptionIsTrue(uri, "tlsallowinvalidhostnames")) {
            v.add(new Violation("TLS_VALIDATION_RELAXED",
                    "certificate and hostname validation must not be relaxed (tlsInsecure / tlsAllowInvalid*)"));
        }
        if (cs.getCredential() == null) {
            v.add(new Violation("CREDENTIALS_REQUIRED", "the connection string must carry credentials (a dedicated database user)"));
        }
        if ((cs.getRequiredReplicaSetName() == null || cs.getRequiredReplicaSetName().isBlank()) && !cs.isSrvProtocol()) {
            v.add(new Violation("REPLICA_SET_REQUIRED",
                    "a replica-set topology is required (replicaSet=... or an SRV connection string): every write uses a transaction"));
        }
        if (Boolean.TRUE.equals(cs.isDirectConnection())) {
            v.add(new Violation("DIRECT_CONNECTION_FORBIDDEN", "directConnection=true bypasses replica-set discovery and is not allowed"));
        }
        if (rawOptionIsTrue(uri, "tlsdisableocspendpointcheck") || rawOptionIsTrue(uri, "tlsdisablecertificaterevocationcheck")) {
            v.add(new Violation("TLS_REVOCATION_CHECK_DISABLED",
                    "certificate revocation checking must not be disabled (tlsDisableOCSPEndpointCheck / tlsDisableCertificateRevocationCheck)"));
        }
        if (hasProxyOption(uri)) {
            v.add(new Violation("PROXY_FORBIDDEN",
                    "a proxy changes the network route to the datastore and is not allowed (proxyHost / proxyPort / proxyUsername / proxyPassword)"));
        }
        if (cs.getHosts().stream().anyMatch(ConnectionContract::mayBeLoopback)) {
            v.add(new Violation("LOOPBACK_HOST_FORBIDDEN",
                    "a loopback host is not allowed here (this is the default when MONGODB_URI was never set)"));
        }
        if (!Boolean.TRUE.equals(cs.getRetryWritesValue())) {
            v.add(new Violation("RETRY_WRITES_REQUIRED", "retryWrites=true must be stated explicitly"));
        }
        if (!Boolean.TRUE.equals(cs.getRetryReads())) {
            v.add(new Violation("RETRY_READS_REQUIRED", "retryReads=true must be stated explicitly"));
        }
        if (cs.getWriteConcern() == null || !"majority".equals(cs.getWriteConcern().getWObject())) {
            v.add(new Violation("WRITE_CONCERN_MAJORITY_REQUIRED", "w=majority must be stated explicitly"));
        }
        if (cs.getWriteConcern() != null && Boolean.FALSE.equals(cs.getWriteConcern().getJournal())) {
            v.add(new Violation("JOURNAL_DISABLED_FORBIDDEN",
                    "journal=false lets a majority write be acknowledged before it is journaled; leave journal unset or true"));
        }
        if (cs.getWriteConcern() != null) {
            Integer wtimeout = cs.getWriteConcern().getWTimeout(TimeUnit.MILLISECONDS);
            if (wtimeout != null && wtimeout > 0 && wtimeout < MIN_WTIMEOUT_MS) {
                v.add(new Violation("WTIMEOUT_TOO_LOW", "wtimeoutMS, when set, must be 0 (no limit) or at least " + MIN_WTIMEOUT_MS
                        + " ms (a tiny value makes majority acknowledgement fail spuriously)"));
            }
        }
        if (cs.getReadConcern() == null || cs.getReadConcern().getLevel() != ReadConcernLevel.MAJORITY) {
            v.add(new Violation("READ_CONCERN_MAJORITY_REQUIRED", "readConcernLevel=majority must be stated explicitly"));
        }
        if (cs.getReadPreference() == null || !"primary".equals(cs.getReadPreference().getName())) {
            v.add(new Violation("READ_PREFERENCE_PRIMARY_REQUIRED", "readPreference=primary must be stated explicitly"));
        }
        requireBounded(v, "CONNECT_TIMEOUT", "connectTimeoutMS", cs.getConnectTimeout(), MIN_TIMEOUT_MS, MAX_CONNECT_TIMEOUT_MS);
        requireBounded(v, "SERVER_SELECTION_TIMEOUT", "serverSelectionTimeoutMS", cs.getServerSelectionTimeout(),
                MIN_TIMEOUT_MS, MAX_SERVER_SELECTION_TIMEOUT_MS);
        Integer socket = cs.getSocketTimeout();
        if (socket != null && socket != 0 && socket < MIN_SOCKET_TIMEOUT_MS) {
            v.add(new Violation("SOCKET_TIMEOUT_TOO_LOW", "socketTimeoutMS, when set, must be 0 or at least " + MIN_SOCKET_TIMEOUT_MS
                    + " (a tight socket timeout aborts an in-flight transaction with an ambiguous outcome)"));
        }
        Integer wait = cs.getMaxWaitTime();
        if (wait != null && (wait < 0 || wait > MAX_WAIT_QUEUE_MS)) {
            v.add(new Violation("WAIT_QUEUE_TIMEOUT_OUT_OF_RANGE", "waitQueueTimeoutMS, when set, must be at most " + MAX_WAIT_QUEUE_MS));
        }
        Integer max = cs.getMaxConnectionPoolSize();
        if (max == null || max < 1 || max > MAX_POOL_SIZE) {
            v.add(new Violation("POOL_SIZE_REQUIRED", "maxPoolSize must be stated explicitly, between 1 and " + MAX_POOL_SIZE));
        }
        Integer min = cs.getMinConnectionPoolSize();
        if (min != null && max != null && min > max) {
            v.add(new Violation("POOL_MIN_ABOVE_MAX", "minPoolSize must not exceed maxPoolSize"));
        }
        return v;
    }

    private static void requireBounded(List<Violation> v, String code, String option, Integer value, int min, int max) {
        if (value == null) {
            v.add(new Violation(code + "_REQUIRED", option + " must be stated explicitly (" + min + ".." + max + " ms)"));
        } else if (value < min || value > max) {
            v.add(new Violation(code + "_OUT_OF_RANGE", option + " must be between " + min + " and " + max + " ms"));
        }
    }

    /** True when any host is a loopback / wildcard address. */
    public static boolean anyLoopbackHost(String uri) {
        try {
            return new ConnectionString(uri).getHosts().stream().anyMatch(ConnectionContract::mayBeLoopback);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * THE authoritative local-target decision (used by the startup verifier and, through {@code MigrationTarget}, by DB-3's
     * {@code TargetGuard}): true only for a demonstrably local target: a plain (non-SRV) connection string with at least one
     * host, EVERY host a provable loopback (see {@link #isLoopback}), and NO proxy option (a proxy changes the effective
     * network destination, so a loopback host behind a proxy is not proven local). A remote, private-network, wildcard,
     * ambiguously spelled, SRV, mixed, proxied, blank or unparseable target is NOT local. No DNS lookup is ever made.
     */
    public static boolean isLocalTarget(String uri) {
        if (uri == null || uri.isBlank()) {
            return false;
        }
        try {
            ConnectionString cs = new ConnectionString(uri);
            return !cs.isSrvProtocol() && !hasProxyOption(uri) && !cs.getHosts().isEmpty()
                    && cs.getHosts().stream().allMatch(ConnectionContract::isLoopback);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * STRICT: true only when the host unambiguously IS loopback, in a spelling the JDK (and so the MongoDB driver) resolves
     * exactly as written. Accepted: {@code localhost} / {@code localhost.}; a standard dotted-quad decimal address in
     * 127.0.0.0/8 (no leading zeros); a single decimal integer inside 127.0.0.0/8 (confirmed against the JDK's own parse);
     * an IPv6 literal the JDK resolves to loopback ({@code ::1}, IPv4-mapped 127/8). Everything else is NOT local, on
     * purpose: octal/hex/short/leading-zero IPv4 spellings (the JDK reads {@code 0177.0.0.1} as 177.0.0.1), the wildcard
     * {@code 0.0.0.0} / {@code ::} (not a destination), trailing-dot or scoped IP literals, private-network addresses, and
     * every other hostname. No DNS lookup is made: only strictly shaped numeric literals ever reach the JDK parser.
     */
    public static boolean isLoopback(String hostAndPort) {
        if (hostAndPort == null) {
            return false;
        }
        String h = hostAndPort.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            if (end < 0 || !(end == h.length() - 1 || h.substring(end + 1).matches(":[0-9]{1,5}"))) {
                return false;
            }
            return isLoopbackIpv6Literal(h.substring(1, end));
        }
        int firstColon = h.indexOf(':');
        if (firstColon >= 0) {
            if (firstColon != h.lastIndexOf(':')) {
                return isLoopbackIpv6Literal(h); // an unbracketed IPv6 literal such as ::1
            }
            if (!h.substring(firstColon + 1).matches("[0-9]{1,5}")) {
                return false;
            }
            h = h.substring(0, firstColon);
        }
        if (h.equals("localhost") || h.equals("localhost.")) {
            return true;
        }
        return isStrictLoopbackIpv4(h);
    }

    private static boolean isStrictLoopbackIpv4(String h) {
        byte[] expected;
        if (h.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            String[] parts = h.split("\\.");
            expected = new byte[4];
            for (int i = 0; i < 4; i++) {
                if (parts[i].length() > 1 && parts[i].startsWith("0")) {
                    return false; // a leading zero is octal to some parsers and decimal to others: ambiguous
                }
                int v = Integer.parseInt(parts[i]);
                if (v > 255) {
                    return false;
                }
                expected[i] = (byte) v;
            }
        } else if (h.matches("[1-9][0-9]{0,9}")) {
            long v = Long.parseLong(h);
            if (v > 0xFFFFFFFFL) {
                return false;
            }
            expected = new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
        } else {
            return false;
        }
        if ((expected[0] & 0xFF) != 127) {
            return false;
        }
        try {
            // a strictly shaped numeric literal is parsed, never looked up; require the JDK to read it exactly as intended
            InetAddress a = InetAddress.getByName(h);
            return a.isLoopbackAddress() && java.util.Arrays.equals(a.getAddress(), expected);
        } catch (Exception e) {
            return false;
        }
    }

    /** An IPv6 literal (never resolved over DNS) that the JDK reads as loopback; scoped or oddly shaped literals are not local. */
    private static boolean isLoopbackIpv6Literal(String h) {
        if (h.indexOf(':') < 0 || !h.matches("[0-9a-f:.]+")) {
            return false;
        }
        try {
            return InetAddress.getByName("[" + h + "]").isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /** True when any MongoDB proxy option is present at all (the driver honours proxyHost/Port/Username/Password; names are case-insensitive). */
    static boolean hasProxyOption(String uri) {
        return rawOptionPresent(uri, "proxyhost") || rawOptionPresent(uri, "proxyport")
                || rawOptionPresent(uri, "proxyusername") || rawOptionPresent(uri, "proxypassword");
    }

    /**
     * BROAD, deliberately over-inclusive: true when the host MIGHT be loopback in any historic spelling (octal, hex, short,
     * leading-zero, wildcard, scoped, unicode digits...). Used only to FORBID a loopback host in staging/production (fail
     * closed), never to relax anything: relaxing uses {@link #isLoopback}.
     */
    static boolean mayBeLoopback(String hostAndPort) {
        if (hostAndPort == null) {
            return false;
        }
        String h = hostAndPort.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            h = h.substring(1, end < 0 ? h.length() : end);
        } else if (h.indexOf(':') >= 0 && h.indexOf(':') == h.lastIndexOf(':')) {
            h = h.substring(0, h.indexOf(':')); // host:port
        }
        int zone = h.indexOf('%');
        if (zone >= 0) {
            h = h.substring(0, zone); // IPv6 zone id
        }
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1); // "localhost." is the same name
        }
        if (h.isEmpty()) {
            return false;
        }
        if (h.equals("localhost")) {
            return true;
        }
        if (h.indexOf(':') >= 0) {
            return mayBeLoopbackIpv6Literal(h);
        }
        long v4 = parseInetAtonV4(h);
        return v4 >= 0 && ((v4 >>> 24) == 127 || v4 == 0L);
    }

    /** An IPv6 literal (never resolved over DNS): ::1 in any spelling, and IPv4-mapped / compatible 127/8 addresses. */
    private static boolean mayBeLoopbackIpv6Literal(String h) {
        if (!h.matches("[0-9a-f:.]+")) {
            return false; // only a strictly shaped literal may reach the JDK parser (anything else could fall through to a lookup)
        }
        try {
            InetAddress a = InetAddress.getByName("[" + h + "]"); // a literal: no name lookup is performed
            return a.isLoopbackAddress() || a.isAnyLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Parses a numeric IPv4 host the way inet_aton does ({@code a.b.c.d}, {@code a.b.c}, {@code a.b}, {@code a};
     * decimal, 0x-hex or 0-octal parts), so {@code 2130706433}, {@code 0x7f.1} and {@code 0177.0.0.1} are all seen as
     * 127.0.0.1. Returns -1 for anything that is not a numeric address (including host NAMES that merely start with
     * "127.").
     */
    static long parseInetAtonV4(String h) {
        String[] parts = h.split("\\.", -1);
        if (parts.length < 1 || parts.length > 4) {
            return -1;
        }
        long[] n = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            n[i] = parseNumberPart(parts[i]);
            if (n[i] < 0) {
                return -1;
            }
        }
        long result = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            if (n[i] > 255) {
                return -1;
            }
            result |= n[i] << (8 * (3 - i));
        }
        long last = n[parts.length - 1];
        long limit = (1L << (8 * (4 - (parts.length - 1)))) - 1;
        if (last > limit) {
            return -1;
        }
        return result | last;
    }

    private static long parseNumberPart(String p) {
        if (p.isEmpty()) {
            return -1;
        }
        try {
            if (p.startsWith("0x")) {
                return p.length() == 2 ? -1 : Long.parseLong(p.substring(2), 16);
            }
            if (p.length() > 1 && p.startsWith("0")) {
                return Long.parseLong(p.substring(1), 8);
            }
            return Long.parseLong(p, 10);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Raw query-string option test (option names are case-insensitive); the driver exposes no getter for some TLS flags. */
    private static boolean rawOptionIsTrue(String uri, String optionLowercase) {
        int q = uri.indexOf('?');
        if (q < 0) {
            return false;
        }
        for (String pair : uri.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).toLowerCase(Locale.ROOT).equals(optionLowercase)
                    && pair.substring(eq + 1).equalsIgnoreCase("true")) {
                return true;
            }
        }
        return false;
    }

    /** True when the option name appears at all in the query string (case-insensitive), whatever its value. */
    private static boolean rawOptionPresent(String uri, String optionLowercase) {
        int q = uri.indexOf('?');
        if (q < 0) {
            return false;
        }
        for (String pair : uri.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            String name = (eq < 0 ? pair : pair.substring(0, eq)).toLowerCase(Locale.ROOT);
            if (name.equals(optionLowercase)) {
                return true;
            }
        }
        return false;
    }
}
