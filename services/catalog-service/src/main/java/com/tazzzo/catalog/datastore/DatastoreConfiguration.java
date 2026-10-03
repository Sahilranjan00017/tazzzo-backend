package com.tazzzo.catalog.datastore;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.migration.MigrationProperties;
import com.tazzzo.catalog.schema.SchemaBootstrap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the fail-fast datastore verifier (DB-4). It runs before the migration startup runner (see its order). */
@Configuration
@EnableConfigurationProperties({DatastoreProperties.class, MigrationProperties.class})
public class DatastoreConfiguration {

    @Bean
    public DatastoreStartupVerifier datastoreStartupVerifier(MongoClient client, MongoDatabase db,
                                                             MigrationProperties migration, DatastoreProperties properties,
                                                             @Value("${spring.data.mongodb.uri:}") String uri) {
        return new DatastoreStartupVerifier(client, db.getName(), migration, properties, uri, SchemaBootstrap.COLLECTIONS);
    }
}
