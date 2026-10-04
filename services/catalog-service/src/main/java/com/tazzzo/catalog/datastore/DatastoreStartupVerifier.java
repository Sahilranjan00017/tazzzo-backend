package com.tazzzo.catalog.datastore;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoConfigurationException;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.tazzzo.catalog.migration.MigrationMode;
import com.tazzzo.catalog.migration.MigrationProperties;
import com.tazzzo.catalog.migration.TargetGuard;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Fail-fast datastore verification at startup (DB-4), run BEFORE the migration startup runner. Normal runtime
 * must hold no schema authority and must be connected the way the contract says; if it is not, the process refuses
 * to serve rather than limping on (and never "fixes" the schema to make startup pass).
 *
 * <p><b>What the ordering guarantees.</b> This runner precedes the migration startup runner and every other
 * {@code ApplicationRunner}. It does NOT precede {@code @Scheduled} work, which starts at context refresh; that is
 * closed off by {@link DatastoreReadiness} and {@link GatedTaskScheduler}: no scheduled worker acts until this
 * verification has succeeded and startup has completed (a refused process runs none, a migration job runs none).
 * Beans created during context refresh may still open (lazy) connections; they never write.
 *
 * <p><b>Enforced</b> for {@code staging} and {@code production}; for any environment label whose connection string is
 * NOT a loopback-only, non-SRV target (an environment name is metadata, not a security boundary: {@code dev}/{@code test}/
 * {@code local} pointing at a remote cluster are enforced like production); for an unidentified environment that points
 * at a non-loopback server; for an unknown environment name; and everywhere when
 * {@code tazzzo.datastore.privilege-verification=ENFORCE}. Only a loopback-only target under {@code local}/{@code test}/
 * {@code dev} (or no environment at all) is advisory: findings are logged and the database is not touched.
 *
 * <p>When enforced it checks, in order, and stops at the first failing stage: (1) the connection-string
 * {@link ConnectionContract} (no connection is made if it fails); (2) reachability and authentication
 * (a bounded {@code hello}); (3) {@link TopologyCheck} (replica set, sessions, server version); (4) the identity's
 * privileges against the {@link PrivilegeProfile} that matches the migration mode (VERIFY = runtime, DRY_RUN = read,
 * APPLY = migrator). Failures are classified and sanitized: they never contain the connection string, host, user name or password.
 */
public class DatastoreStartupVerifier implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DatastoreStartupVerifier.class);

    public enum Enforcement { ENFORCED, ADVISORY }

    public record Result(Enforcement enforcement, String environment, PrivilegeProfile profile,
                         List<Violation> violations, String summary) {
        public boolean ok() {
            return violations.isEmpty();
        }
    }

    private final MongoClient client;
    private final String database;
    private final MigrationProperties migration;
    private final DatastoreProperties properties;
    private final String uri;
    private final Collection<String> businessCollections;
    private final DatastoreReadiness readiness;

    public DatastoreStartupVerifier(MongoClient client, String database, MigrationProperties migration,
                                    DatastoreProperties properties, String uri, Collection<String> businessCollections) {
        this(client, database, migration, properties, uri, businessCollections, new DatastoreReadiness());
    }

    public DatastoreStartupVerifier(MongoClient client, String database, MigrationProperties migration,
                                    DatastoreProperties properties, String uri, Collection<String> businessCollections,
                                    DatastoreReadiness readiness) {
        this.readiness = readiness;
        this.client = client;
        this.database = database;
        this.migration = migration;
        this.properties = properties;
        this.uri = uri == null ? "" : uri;
        this.businessCollections = List.copyOf(businessCollections);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    static PrivilegeProfile profileFor(MigrationMode mode) {
        return switch (mode) {
            case VERIFY -> PrivilegeProfile.RUNTIME;
            case DRY_RUN -> PrivilegeProfile.MIGRATION_READ;
            case APPLY, APPLY_ON_STARTUP, LEGACY -> PrivilegeProfile.MIGRATION_APPLY;
        };
    }

    public Result verify() {
        String env = migration.getEnvironment() == null ? "" : migration.getEnvironment();
        PrivilegeProfile profile = profileFor(migration.getMode());
        List<Violation> findings = new ArrayList<>(ConnectionContract.evaluate(uri));

        Enforcement enforcement = enforcementFor(env, findings);
        if (enforcement == Enforcement.ADVISORY) {
            return new Result(enforcement, env, profile, findings, "not enforced for environment '" + (env.isBlank() ? "(unset)" : env) + "' (loopback target)");
        }
        if (!findings.isEmpty()) {
            return new Result(enforcement, env, profile, findings, "connection string violates the contract");
        }
        List<Violation> live = new ArrayList<>();
        Document hello = null;
        try {
            hello = client.getDatabase("admin").runCommand(new Document("hello", 1));
        } catch (RuntimeException e) {
            live.add(classify(e, "hello"));
        }
        if (live.isEmpty()) {
            live.addAll(TopologyCheck.evaluate(hello));
        }
        if (live.isEmpty()) {
            try {
                Document status = client.getDatabase("admin").runCommand(new Document("connectionStatus", 1).append("showPrivileges", true));
                live.addAll(PrivilegeInspector.inspect(status, database, businessCollections, profile));
            } catch (RuntimeException e) {
                live.add(classify(e, "connectionStatus"));
            }
        }
        String summary = live.isEmpty()
                ? "environment=" + env + " database=" + database + " profile=" + profile + " replicaSet=true wireVersion="
                        + hello.get("maxWireVersion") + " privileges=ok"
                : "live datastore checks failed";
        return new Result(enforcement, env, profile, live, summary);
    }

    private Enforcement enforcementFor(String env, List<Violation> findings) {
        if (properties.getPrivilegeVerification() == DatastoreProperties.Verification.ENFORCE) {
            return Enforcement.ENFORCED;
        }
        if (env.equals("staging") || env.equals("production")) {
            return Enforcement.ENFORCED;
        }
        if (env.isBlank()) {
            if (!uri.isBlank() && !ConnectionContract.isLocalTarget(uri)) {
                findings.add(new Violation("ENVIRONMENT_NOT_IDENTIFIED", "the environment is not identified (tazzzo.migration.environment) "
                        + "but the connection string points at a non-loopback server; set it to one of " + TargetGuard.KNOWN_ENVIRONMENTS));
                return Enforcement.ENFORCED;
            }
            return Enforcement.ADVISORY;
        }
        if (!TargetGuard.KNOWN_ENVIRONMENTS.contains(env)) {
            findings.add(new Violation("ENVIRONMENT_UNKNOWN", "the environment name is not one of " + TargetGuard.KNOWN_ENVIRONMENTS));
            return Enforcement.ENFORCED;
        }
        // local / test / dev: relaxed ONLY for a demonstrably local target. A remote, SRV, mixed or unparseable target
        // is enforced whatever the label says (the label is metadata, not a boundary).
        if (!uri.isBlank() && !ConnectionContract.isLocalTarget(uri)) {
            return Enforcement.ENFORCED;
        }
        return Enforcement.ADVISORY;
    }

    /** Turns a driver failure into a fixed, secret-free finding. */
    static Violation classify(Throwable failure, String command) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof MongoSecurityException) {
                return new Violation("AUTHENTICATION_FAILED",
                        "the database rejected the credentials (check the secret value and the authentication database)");
            }
            if (t instanceof MongoCommandException c) {
                if (c.getErrorCode() == 18) {
                    return new Violation("AUTHENTICATION_FAILED",
                            "the database rejected the credentials (check the secret value and the authentication database)");
                }
                if (c.getErrorCode() == 13) {
                    return new Violation("UNAUTHORIZED_COMMAND", "the identity is not authorized to run '" + command + "'");
                }
            }
            if (t instanceof MongoTimeoutException) {
                return new Violation("DATASTORE_UNAVAILABLE", "no database server became available within the configured "
                        + "serverSelectionTimeoutMS (check the network allow-list, the connection string and that the cluster is running)");
            }
            if (t instanceof MongoConfigurationException) {
                return new Violation("DATASTORE_CONFIGURATION_ERROR", "the connection string could not be resolved to a server");
            }
        }
        return new Violation("DATASTORE_CHECK_FAILED", "the datastore check '" + command + "' failed (" + failure.getClass().getSimpleName() + ")");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (migration.getMode() == MigrationMode.DRY_RUN || migration.getMode() == MigrationMode.APPLY) {
            readiness.markJob(); // a migration job runs no business workers, whatever the scheduler flag says
        }
        Result r;
        try {
            r = verify();
        } catch (RuntimeException e) {
            readiness.markRefused();
            throw e;
        }
        String context = "environment=" + (r.environment().isBlank() ? "(unset)" : r.environment()) + ", profile=" + r.profile();
        if (r.enforcement() == Enforcement.ENFORCED) {
            if (!r.ok()) {
                readiness.markRefused();
                throw new DatastoreContractException(context, r.violations());
            }
            log.info("datastore verified: {}", r.summary());
            readiness.markVerified();
            return;
        }
        readiness.markVerified();
        if (r.ok()) {
            log.info("datastore contract advisory: satisfied ({})", r.summary());
        } else {
            log.info("datastore contract advisory ({}): {} finding(s) {}", r.summary(), r.violations().size(),
                    r.violations().stream().map(Violation::code).toList());
        }
    }
}
