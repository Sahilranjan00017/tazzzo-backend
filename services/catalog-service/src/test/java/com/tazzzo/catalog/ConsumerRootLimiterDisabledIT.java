package com.tazzzo.catalog;

import com.tazzzo.common.audit.TestActors;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DISABLED is the INTENTIONAL fail-closed state, not \"unlimited\". No limiter bean exists, so the
 * consumer surface cannot serve — Q4-d forbids exposing it while it cannot be limited.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerRootLimiterDisabledIT extends AbstractConsumerIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.mongodb.database", () -> "tazzzo_root_disabled_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.consumer-rate-limit.mode", () -> "DISABLED");
    }

    @BeforeAll
    void seed() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline(TestActors.TEST, "R1");
        eligibleProduct("TZP-1", "TZV-000001");
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.context.ApplicationContext context;

    /**
     * CatalogApplication excludes DataRedisAutoConfiguration / DataRedisRepositoriesAutoConfiguration (the Boot 4
     * names): with the limiter DISABLED nothing may build a default localhost Redis connection.
     */
    @Test
    void no_redis_connection_factory_or_template_bean_exists_when_the_limiter_is_disabled() throws Exception {
        for (String type : new String[]{"org.springframework.data.redis.connection.RedisConnectionFactory",
                "org.springframework.data.redis.core.StringRedisTemplate",
                "org.springframework.data.redis.core.RedisTemplate"}) {
            assertThat(context.getBeanNamesForType(Class.forName(type))).as(type).isEmpty();
        }
    }

    @Test
    void a_disabled_limiter_means_the_consumer_surface_refuses_to_serve() {
        PRODUCT_FINDS.set(0);
        ResponseEntity<JsonNode> res = get("/catalog/v1/categories", JsonNode.class);

        assertThat(res.getStatusCode().value())
                .as("DISABLED is fail-closed, never permissive").isEqualTo(503);
        assertThat(res.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(PRODUCT_FINDS.get()).isZero();
    }
}
