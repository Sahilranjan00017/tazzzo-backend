package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Operation ids are the identifiers generated clients (CMS, storefront) compile against. They must be unique,
 * springdoc's order-dependent numeric suffixes ({@code get_1}) must never reappear, and the ids that were pinned
 * explicitly in earlier PRs must keep their exact spelling.
 */
class OperationIdContractIT extends AbstractApiIT {

    static final Set<String> PINNED = Set.of(
            "listStock",
            "createImportJob", "appendImportJobRows", "correctImportJobRow", "validateImportJob", "applyImportJob",
            "resumeImportJob", "cancelImportJob", "listImportJobs", "getImportJob", "listImportJobRows",
            "downloadImportJobErrorsCsv");

    @Test
    void operation_ids_are_unique_stable_and_well_formed() {
        ResponseEntity<JsonNode> res = get("/v3/api-docs", READ_TOKEN, JsonNode.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        List<String> ids = new ArrayList<>();
        res.getBody().at("/paths").fields().forEachRemaining(p -> p.getValue().fields().forEachRemaining(op -> {
            if (op.getValue().has("operationId")) ids.add(op.getValue().get("operationId").asText());
            else ids.add("<missing>@" + op.getKey() + " " + p.getKey());
        }));
        assertThat(ids).hasSizeGreaterThan(100);
        Set<String> seen = new HashSet<>();
        List<String> dups = ids.stream().filter(i -> !seen.add(i)).toList();
        assertThat(dups).as("duplicate operationIds").isEmpty();
        assertThat(ids).as("springdoc numeric de-dup suffix is order-dependent").noneMatch(i -> i.matches(".*_\\d+$"));
        assertThat(ids).allMatch(i -> i.matches("^[a-z][A-Za-z0-9]*$"));
        assertThat(ids).as("explicitly pinned ids").containsAll(PINNED);
    }
}
