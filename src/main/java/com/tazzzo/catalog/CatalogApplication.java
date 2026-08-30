package com.tazzzo.catalog;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.schema.SchemaBootstrap;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }

    @Bean
    public MongoDatabase mongoDatabase(MongoClient client,
                                       @Value("${spring.data.mongodb.database:tazzzo}") String dbName) {
        return client.getDatabase(dbName);
    }

    @Bean
    public ApplicationRunner schemaBootstrapRunner(SchemaBootstrap bootstrap,
                                                   TaxonomyLoader taxonomyLoader,
                                                   MongoDatabase db,
                                                   @Value("${tazzzo.schema.bootstrap-on-startup:false}") boolean enabled,
                                                   @Value("${tazzzo.schema.load-taxonomy-seed:false}") boolean loadSeed) {
        return args -> {
            if (enabled) {
                bootstrap.bootstrap(db);
            }
            if (loadSeed) {
                // insert-only: safe on every restart, never clobbers changed nodes
                TaxonomyLoader.LoadResult r = taxonomyLoader.load(db);
                LoggerFactory.getLogger(CatalogApplication.class).info(
                        "taxonomy seed: {} nodes, {} aliases, {} definitions, {} schemas",
                        r.nodes(), r.aliases(), r.definitions(), r.schemas());
            }
        };
    }
}
