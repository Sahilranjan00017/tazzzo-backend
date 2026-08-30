package com.tazzzo.catalog;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.schema.SchemaBootstrap;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

/** Real HTTP against a real MongoDB — the API is exercised as a client would. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractApiIT {

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    static {
        MONGO.start();
    }

    static final String CMS_TOKEN = "cms-test-token";
    static final String READ_TOKEN = "read-test-token";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_api_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        // M1: background workers must NOT run inside these suites — MergeCrashIT,
        // TaintCrashIT and RollupStallIT assert crash WINDOWS that a live scheduler closes.
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.auth.cms-token", () -> CMS_TOKEN);
        r.add("tazzzo.auth.read-token", () -> READ_TOKEN);
    }

    @LocalServerPort protected int port;
    @Autowired protected TestRestTemplate rest;

    @org.junit.jupiter.api.BeforeAll
    void useApacheClient() {
        // JDK HttpURLConnection supports neither PATCH nor 401-retry in streaming mode;
        // Apache HttpClient 5 handles both, so the tests exercise real HTTP semantics.
        rest.getRestTemplate().setRequestFactory(
                new org.springframework.http.client.HttpComponentsClientHttpRequestFactory());
    }
    @Autowired protected MongoClient client;
    @Autowired protected MongoDatabase db;
    @Autowired protected SchemaBootstrap schemaBootstrap;

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    protected HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        return h;
    }

    protected <T> ResponseEntity<T> post(String path, Object body, String token, Class<T> type) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers(token)), type);
    }

    protected <T> ResponseEntity<T> get(String path, String token, Class<T> type) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers(token)), type);
    }
}
