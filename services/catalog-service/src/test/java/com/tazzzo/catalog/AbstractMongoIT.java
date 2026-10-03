package com.tazzzo.catalog;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.migration.MigrationRunner;
import com.tazzzo.catalog.migration.MigrationTarget;
import com.tazzzo.catalog.schema.SchemaBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import java.util.List;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractMongoIT {

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    static {
        MONGO.start(); // one container for the whole suite
    }

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("spring.data.mongodb.database", () -> "tazzzo_it");
        registry.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        // M1: background workers must NOT run inside these suites — MergeCrashIT,
        // TaintCrashIT and RollupStallIT assert crash WINDOWS that a live scheduler closes.
        registry.add("tazzzo.scheduler.enabled", () -> "false");
        // Q5-c: the limiter mode has NO production default, so every Spring context must state
        // it. DISABLED is the fail-closed state; these suites exercise no consumer surface.
        registry.add("tazzzo.consumer-rate-limit.mode", () -> "DISABLED");
    }

    @Autowired protected MongoClient client;
    @Autowired protected MongoDatabase db;
    @Autowired protected SchemaBootstrap schemaBootstrap;
    @Autowired protected MigrationRunner migrationRunner;

    @BeforeAll
    void resetDatabase() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        applySchemaMigrations();
    }

    /**
     * The schema migrations a deployed database has (cursor index, unique-link indexes). Run through the real
     * runner so every suite also exercises the framework. Seed and data migrations are NOT applied: suites that
     * need taxonomy data load it explicitly, as before.
     */
    protected void applySchemaMigrations() {
        MigrationRunner.RunReport r = migrationRunner.apply(
                new MigrationTarget("test", db.getName(), List.of(), "tests", "test"),
                MigrationRunner.Selection.schemaOnly(), MigrationRunner.ApplyOptions.forTests());
        if (!r.ok()) throw new AssertionError("schema migrations failed in test setup: " + r.render());
    }
}
