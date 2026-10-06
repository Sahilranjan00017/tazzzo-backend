package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class ProbeListIT extends AbstractApiIT {
    @Test
    void probe() {
        for (String p : new String[]{"/api/v1/products", "/api/v1/products?limit=5", "/api/v1/taxonomy/nodes", "/api/v1/taxonomy/releases",
                "/v1/products", "/v1/products?limit=5", "/catalog/v1/products", "/api/v1/products/TZP-NOPE", "/api/v1/products?canonicalKey=x"}) {
            ResponseEntity<String> r = get(p, CMS_TOKEN, String.class);
            System.out.println("PROBE " + p + " -> " + r.getStatusCode().value() + " " + String.valueOf(r.getBody()).replace("\n", " "));
        }
    }
}
