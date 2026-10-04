package com.tazzzo.catalog.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.datastore.DatastoreReadiness;
import com.tazzzo.catalog.schema.DiscriminatingAttributeRegistry;
import com.tazzzo.catalog.schema.SchemaBootstrap;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires the migration framework and replaces the former unconditional startup bootstrap (R5). */
@Configuration
@EnableConfigurationProperties(MigrationProperties.class)
public class MigrationConfiguration {

    @Bean
    public MigrationRunner migrationRunner(MongoDatabase db, SchemaBootstrap bootstrap, TaxonomyLoader loader) {
        return new MigrationRunner(db, Migrations.defaults(bootstrap, loader), new MigrationHistory(db),
                new MigrationLock(db), Clock.systemUTC());
    }

    /** Production exit: terminate the JVM with the job's exit code. Tests supply a recording {@code @Primary} bean. */
    @Bean
    public MigrationExitHandler migrationExitHandler(ConfigurableApplicationContext context) {
        return code -> System.exit(SpringApplication.exit(context, () -> code));
    }

    @Bean
    public ApplicationRunner schemaBootstrapRunner(MigrationRunner runner, MigrationProperties props, MongoClient client,
                                                   MongoDatabase db, SchemaBootstrap bootstrap, TaxonomyLoader loader,
                                                   DiscriminatingAttributeRegistry discriminators,
                                                   MigrationExitHandler exitHandler, DatastoreReadiness readiness,
                                                   @Value("${tazzzo.schema.bootstrap-on-startup:false}") boolean legacyBootstrap,
                                                   @Value("${tazzzo.schema.load-taxonomy-seed:false}") boolean legacyLoadSeed) {
        return new MigrationStartupRunner(runner, props, client, db, bootstrap, loader, discriminators,
                legacyBootstrap, legacyLoadSeed, exitHandler::exit, readiness);
    }
}
