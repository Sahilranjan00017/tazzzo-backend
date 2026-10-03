package com.tazzzo.catalog.datastore;

import com.mongodb.ConnectionString;
import com.mongodb.ReadConcernLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 */
public final class ConnectionContract {

    public static final int MIN_TIMEOUT_MS = 1_000;
    public static final int MAX_CONNECT_TIMEOUT_MS = 15_000;
    public static final int MAX_SERVER_SELECTION_TIMEOUT_MS = 30_000;
    public static final int MIN_SOCKET_TIMEOUT_MS = 60_000;
    public static final int MAX_WAIT_QUEUE_MS = 30_000;
    public static final int MAX_POOL_SIZE = 100;

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
        if (cs.getRequiredReplicaSetName() == null && !cs.isSrvProtocol()) {
            v.add(new Violation("REPLICA_SET_REQUIRED",
                    "a replica-set topology is required (replicaSet=... or an SRV connection string): every write uses a transaction"));
        }
        if (Boolean.TRUE.equals(cs.isDirectConnection())) {
            v.add(new Violation("DIRECT_CONNECTION_FORBIDDEN", "directConnection=true bypasses replica-set discovery and is not allowed"));
        }
        if (cs.getHosts().stream().anyMatch(ConnectionContract::isLoopback)) {
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
            return new ConnectionString(uri).getHosts().stream().anyMatch(ConnectionContract::isLoopback);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isLoopback(String hostAndPort) {
        String h = hostAndPort.toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            h = h.substring(1, h.indexOf(']') < 0 ? h.length() : h.indexOf(']'));
        } else if (h.contains(":")) {
            h = h.substring(0, h.indexOf(':'));
        }
        return h.equals("localhost") || h.equals("::1") || h.equals("0.0.0.0") || h.startsWith("127.");
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
}
