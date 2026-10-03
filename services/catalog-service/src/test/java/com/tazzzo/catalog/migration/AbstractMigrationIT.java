package com.tazzzo.catalog.migration;

import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Base for migration-framework suites. Every test works on its OWN scratch database (random name) so it can
 * start from a truly empty database and cannot disturb the shared one the other suites use.
 */
abstract class AbstractMigrationIT extends AbstractMongoIT {

    @Autowired protected TaxonomyLoader taxonomyLoader;

    private final List<MongoDatabase> scratchDatabases = new ArrayList<>();

    protected MongoDatabase scratch() {
        MongoDatabase d = client.getDatabase("db3_" + UUID.randomUUID().toString().replace("-", ""));
        scratchDatabases.add(d);
        return d;
    }

    @AfterEach
    void dropScratchDatabases() {
        scratchDatabases.forEach(MongoDatabase::drop);
        scratchDatabases.clear();
    }

    protected MigrationTarget target(MongoDatabase d) {
        return new MigrationTarget("test", d.getName(), List.of("test-host:27017"), "tests", "build-1");
    }

    protected MigrationRunner runner(MongoDatabase d, List<Migration> registry) {
        return new MigrationRunner(d, registry, new MigrationHistory(d), new MigrationLock(d), Clock.systemUTC());
    }

    protected MigrationRunner realRunner(MongoDatabase d) {
        return runner(d, Migrations.defaults(schemaBootstrap, taxonomyLoader));
    }

    protected MigrationRunner.ApplyOptions apply(Duration lease, Duration lockWait) {
        return new MigrationRunner.ApplyOptions(MigrationMode.APPLY_ON_STARTUP, null, null, lease, lockWait);
    }

    protected MigrationRunner.ApplyOptions apply() {
        return apply(Duration.ofMinutes(5), Duration.ofSeconds(30));
    }

    protected static Document history(MongoDatabase d, String id) {
        return d.getCollection(MigrationHistory.COLLECTION).find(new Document("_id", id)).first();
    }

    protected static List<String> collectionNames(MongoDatabase d) {
        return d.listCollectionNames().into(new ArrayList<>());
    }

    /** Index definitions of every BUSINESS collection; the runner's own bookkeeping collections are excluded. */
    protected static String indexSnapshot(MongoDatabase d) {
        List<String> all = new ArrayList<>();
        for (String c : d.listCollectionNames()) {
            if (MigrationHistory.COLLECTION.equals(c) || MigrationLock.COLLECTION.equals(c)) continue;
            d.getCollection(c).listIndexes().forEach(i -> all.add(c + ":" + i.toJson()));
        }
        all.sort(String::compareTo);
        return String.join("\n", all);
    }
}
