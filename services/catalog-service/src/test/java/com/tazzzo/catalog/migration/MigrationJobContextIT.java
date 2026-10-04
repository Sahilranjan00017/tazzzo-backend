package com.tazzzo.catalog.migration;

import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MongoDBContainer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migration job exactly as the runbook documents it: the real application started as a one-shot NON-WEB process
 * (web-application-type none) in mode DRY_RUN with {@code exit-after-run=true}, with
 * ONLY the configuration the runbook lists (database, scheduler off, and the mandatory consumer rate-limit mode, which
 * the application requires and has no default for). It deliberately does NOT extend {@code AbstractMongoIT}, whose
 * injected properties would otherwise hide missing configuration. The exit action is observed, not executed.
 *
 * <p>The environment is {@code dev} on purpose (DB-4): for {@code staging}/{@code production} the datastore verifier requires a
 * contract-compliant connection string (TLS, credentials, replica set, explicit options), which a throw-away
 * Testcontainers server cannot offer. The staging job shape, its refusal of a non-compliant URI and the privilege checks are
 * covered by {@code DatastoreWiringIT} and {@code DatastorePrivilegeIT} (real authenticated replica set).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "tazzzo.migration.mode=DRY_RUN",
        "tazzzo.migration.environment=dev",
        "tazzzo.migration.exit-after-run=true",
        "tazzzo.schema.load-taxonomy-seed=false",
        "tazzzo.scheduler.enabled=false",
        "tazzzo.consumer-rate-limit.mode=DISABLED",
        "spring.data.mongodb.database=tazzzo_job_context_it"})
@Import(MigrationJobContextIT.RecordingExit.class)
class MigrationJobContextIT {

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");
    static final List<Integer> EXIT_CODES = new CopyOnWriteArrayList<>();

    static {
        MONGO.start();
    }

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
    }

    @TestConfiguration
    static class RecordingExit {
        @Bean
        @Primary
        MigrationExitHandler recordingExit() {
            return EXIT_CODES::add;
        }
    }

    @Autowired ApplicationContext context;
    @Autowired MongoDatabase db;

    @Test
    void the_runbook_job_shape_runs_the_dry_run_exits_zero_and_mutates_nothing() {
        assertThat(context).as("no web server in job mode").isNotInstanceOf(WebApplicationContext.class);
        assertThat(EXIT_CODES).as("the job finished and asked the process to exit with code 0").containsExactly(0);
        assertThat(db.listCollectionNames()).as("a dry-run job creates nothing — not even the history or lock").isEmpty();
    }
}
