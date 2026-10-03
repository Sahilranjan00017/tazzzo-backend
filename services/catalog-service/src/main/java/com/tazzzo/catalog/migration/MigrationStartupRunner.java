package com.tazzzo.catalog.migration;

import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.schema.DiscriminatingAttributeRegistry;
import com.tazzzo.catalog.schema.SchemaBootstrap;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Startup behaviour for database evolution (R5). Three kinds of activity are kept apart:
 * <ul>
 *   <li><b>A. runtime verification</b> (default {@code VERIFY}): read-only; the application refuses to serve if
 *       the database is not at the required migration state;</li>
 *   <li><b>B. migration mutation</b>: only via {@code APPLY} (a controlled job, locked and recorded) or, for
 *       local/test/dev only, {@code APPLY_ON_STARTUP};</li>
 *   <li><b>C. reference initialization</b>: insert-if-absent seed, a migration (V0003), not a restart side effect.</li>
 * </ul>
 * {@code LEGACY} reproduces the pre-DB-3 flag-driven behaviour for local/test/dev and is refused elsewhere.
 * Loading identity ratifications (a read) is unconditional, as before.
 */
public class MigrationStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationStartupRunner.class);

    private final MigrationRunner runner;
    private final MigrationProperties props;
    private final MongoClient client;
    private final MongoDatabase db;
    private final String databaseName;
    private final SchemaBootstrap bootstrap;
    private final TaxonomyLoader taxonomyLoader;
    private final DiscriminatingAttributeRegistry discriminators;
    private final boolean legacyBootstrap;
    private final boolean legacyLoadSeed;
    private final IntConsumer exit;

    public MigrationStartupRunner(MigrationRunner runner, MigrationProperties props, MongoClient client, MongoDatabase db,
                                  SchemaBootstrap bootstrap, TaxonomyLoader taxonomyLoader,
                                  DiscriminatingAttributeRegistry discriminators, boolean legacyBootstrap,
                                  boolean legacyLoadSeed, IntConsumer exit) {
        this.runner = runner;
        this.props = props;
        this.client = client;
        this.db = db;
        this.databaseName = db.getName();
        this.bootstrap = bootstrap;
        this.taxonomyLoader = taxonomyLoader;
        this.discriminators = discriminators;
        this.legacyBootstrap = legacyBootstrap;
        this.legacyLoadSeed = legacyLoadSeed;
        this.exit = exit;
    }

    MigrationTarget target() {
        List<String> hosts = client.getClusterDescription().getClusterSettings().getHosts().stream()
                .map(ServerAddress::toString).toList();
        String operator = props.getOperator() == null || props.getOperator().isBlank()
                ? System.getProperty("user.name", "unknown") : props.getOperator();
        return new MigrationTarget(props.getEnvironment(), databaseName, hosts, operator, props.getBuildVersion());
    }

    private MigrationRunner.Selection selection() {
        return MigrationRunner.Selection.all()
                .withEnabled(new HashSet<>(props.getEnabledMigrations()))
                .withApproved(new HashSet<>(props.getApprovedDataMigrations()));
    }

    /** Several local instances may start at once under APPLY_ON_STARTUP: they wait for the lock rather than crash. */
    private static final long STARTUP_MIN_LOCK_WAIT_SECONDS = 120;

    private MigrationRunner.ApplyOptions applyOptions(MigrationMode mode) {
        long wait = props.getLockWaitSeconds();
        if (mode == MigrationMode.APPLY_ON_STARTUP) wait = Math.max(wait, STARTUP_MIN_LOCK_WAIT_SECONDS);
        return new MigrationRunner.ApplyOptions(mode, props.getConfirmDatabase(), props.getConfirmEnvironment(),
                Duration.ofSeconds(props.getLockLeaseSeconds()), Duration.ofSeconds(wait));
    }

    @Override
    public void run(ApplicationArguments args) {
        MigrationMode mode = props.getMode();
        MigrationTarget target = target();
        log.info("database evolution mode={} target[{}]", mode, target.describe());
        switch (mode) {
            case LEGACY -> {
                if (legacyBootstrap || legacyLoadSeed) {
                    TargetGuard.requireMutationAllowed(MigrationMode.LEGACY, target, null, null);
                }
                if (legacyBootstrap) bootstrap.bootstrap(db);
            }
            case VERIFY -> verify();
            case APPLY_ON_STARTUP -> {
                MigrationRunner.RunReport r = runner.apply(target, selection(), applyOptions(MigrationMode.APPLY_ON_STARTUP));
                log.info("{}", r.render());
                if (!r.ok()) throw new IllegalStateException("startup migration did not complete: " + r.outcome());
            }
            case DRY_RUN -> {
                MigrationRunner.RunReport r = runner.dryRun(target, selection());
                log.info("{}", r.render());
                finishJob(r.exitCode());
                if (props.isExitAfterRun()) return; // a job that exits ends here; otherwise startup continues normally
            }
            case APPLY -> {
                MigrationRunner.RunReport r = runner.apply(target, selection(), applyOptions(MigrationMode.APPLY));
                log.info("{}", r.render());
                finishJob(r.exitCode());
                if (props.isExitAfterRun()) return;
            }
        }
        int ratifications = discriminators.load(db);
        log.info("identity ratifications loaded: {}", ratifications);
        if (mode == MigrationMode.LEGACY && legacyLoadSeed) {
            TaxonomyLoader.LoadResult r = taxonomyLoader.load(db); // insert-if-absent only
            log.info("taxonomy seed (insert-if-absent): {} nodes, {} aliases, {} definitions, {} schemas",
                    r.nodes(), r.aliases(), r.definitions(), r.schemas());
        }
    }

    private void verify() {
        MigrationRunner.VerifyReport v = runner.verify(selection());
        if (v.ok()) {
            log.info("{}", v.render());
            return;
        }
        if (props.getVerifyFailure() == MigrationProperties.VerifyFailure.FAIL) {
            throw new IllegalStateException("database schema verification failed — refusing to start. " + v.render()
                    + " Run the migration job (mode=DRY_RUN, then mode=APPLY) before starting the application.");
        }
        log.warn("database schema verification failed (verify-failure=WARN, continuing): {}", v.render());
    }

    private void finishJob(int code) {
        if (props.isExitAfterRun()) {
            exit.accept(code);
        } else if (code != 0) {
            throw new IllegalStateException("migration job finished with exit code " + code);
        }
    }
}
