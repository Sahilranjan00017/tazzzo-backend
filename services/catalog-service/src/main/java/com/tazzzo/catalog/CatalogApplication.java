package com.tazzzo.catalog;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

// Q5: the limiter owns its Redis connection and builds it from an explicit, mandatory
// tazzzo.consumer-rate-limit.redis-url. RedisAutoConfiguration is excluded so a localhost
// default connection cannot exist for anything to fall back onto.
// scanBasePackages spans the whole module tree so the commerce.read freshness beans
// (CommerceFreshnessConfig / CommerceProjectionScheduler, both feature-flag gated) are discovered
// — commerce.read is a SIBLING of com.tazzzo.catalog, not a child. This is a string base package,
// not a type reference, so the catalog->commerce ArchUnit boundary is unaffected.
@SpringBootApplication(scanBasePackages = "com.tazzzo", exclude = {
        org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration.class})
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
}
