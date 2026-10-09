package com.tazzzo.catalog.datastore;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.migration.MigrationProperties;
import com.tazzzo.catalog.schema.SchemaBootstrap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the fail-fast datastore verifier (DB-4), the readiness gate and the gated scheduler. */
@Configuration
@EnableConfigurationProperties({DatastoreProperties.class, MigrationProperties.class})
public class DatastoreConfiguration {

    @Bean
    public DatastoreReadiness datastoreReadiness() {
        return new DatastoreReadiness();
    }

    /**
     * The only task scheduler (replaces Spring Boot's auto-configured one, same pool size and thread prefix): every
     * {@code @Scheduled} method runs through the readiness gate, so no scheduled worker acts before verification.
     */
    @Bean(name = "taskScheduler")
    public GatedTaskScheduler taskScheduler(DatastoreReadiness readiness,
                                            @Value("${spring.task.scheduling.pool.size:1}") int poolSize,
                                            @Value("${spring.task.scheduling.thread-name-prefix:scheduling-}") String prefix) {
        GatedTaskScheduler scheduler = new GatedTaskScheduler(readiness);
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(prefix);
        return scheduler;
    }

    @Bean
    public DatastoreStartupVerifier datastoreStartupVerifier(MongoClient client, MongoDatabase db,
                                                             MigrationProperties migration, DatastoreProperties properties,
                                                             DatastoreReadiness readiness,
                                                             @Value("${spring.mongodb.uri:}") String uri) {
        return new DatastoreStartupVerifier(client, db.getName(), migration, properties, uri, SchemaBootstrap.COLLECTIONS, readiness);
    }
}
