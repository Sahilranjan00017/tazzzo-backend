package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exports the OpenAPI contract generated FROM the running controllers — the handoff artifact
 * for CMS, app, importer and (eventually) Camunda. Generated, never hand-written, so it can
 * never drift from the implementation.
 */
class OpenApiExportIT extends AbstractApiIT {

    @Test
    void export_openapi_specification() throws Exception {
        ResponseEntity<JsonNode> res = get("/v3/api-docs", null, JsonNode.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode spec = res.getBody();
        assertThat(spec).isNotNull();
        assertThat(spec.at("/paths").size()).as("all endpoints present").isGreaterThanOrEqualTo(12);
        for (String path : new String[]{"/api/v1/products", "/api/v1/products/{id}",
                "/api/v1/products/{id}/classify", "/api/v1/products/{id}/publish",
                "/api/v1/products/{id}/gtins", "/api/v1/taxonomy/releases",
                "/api/v1/taxonomy/releases/{id}/publish", "/api/v1/taxonomy/nodes/{id}/rename",
                "/api/v1/taxonomy/nodes/{id}/move", "/api/v1/taxonomy/nodes/{id}/merge",
                "/api/v1/attributes", "/api/v1/attribute-schemas/{id}/fields"}) {
            assertThat(spec.at("/paths").has(path)).as("spec contains " + path).isTrue();
        }
        Path out = Path.of("docs/openapi.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(spec));
        System.out.println("OpenAPI written: " + out.toAbsolutePath()
                + " (" + spec.at("/paths").size() + " paths)");
    }
}
