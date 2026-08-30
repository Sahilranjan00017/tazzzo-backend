package com.tazzzo.catalog.api;

import java.util.ArrayList;
import java.util.List;
import com.tazzzo.catalog.api.ApiDtos.*;
import com.tazzzo.catalog.tx.AttributeAuthoringService;
import org.bson.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Thin transport over AttributeAuthoringService. Governance rules stay in the domain. */
@RestController
@RequestMapping("/api/v1")
public class AttributeController {

    private final AttributeAuthoringService authoring;

    public AttributeController(AttributeAuthoringService authoring) {
        this.authoring = authoring;
    }

    @PostMapping("/attributes")
    public ResponseEntity<IdResponse> create(@RequestBody CreateAttributeRequest body) {
        int version = authoring.createDefinition(body.key(), body.type(), body.governance(),
                body.knownValues());
        return ResponseEntity.status(HttpStatus.CREATED).body(new IdResponse(body.key(), version));
    }

    @PostMapping("/attributes/{key}/values")
    public IdResponse addValue(@PathVariable String key, @RequestBody AddEnumValueRequest body) {
        authoring.addEnumValue(key, body.value());
        return new IdResponse(key, null);
    }

    @GetMapping("/attributes/{key}")
    public AttributeResponse getAttribute(@PathVariable String key) {
        Document d = authoring.activeDefinition(key);
        if (d == null) throw new NotFoundException("no such attribute: " + key);
        return new AttributeResponse(d.getString("key"), d.getInteger("version"),
                d.getString("type"), d.getString("governance"),
                d.getList("known_values", String.class), d.getString("status"));
    }

    @PostMapping("/attribute-schemas/{id}/fields")
    public IdResponse addField(@PathVariable String id, @RequestBody AddSchemaFieldRequest body) {
        int version = authoring.addSchemaField(id, body.key(),
                Boolean.TRUE.equals(body.required()), Boolean.TRUE.equals(body.allowBreaking()));
        return new IdResponse(id, version);
    }

    @GetMapping("/attribute-schemas/{id}")
    public SchemaResponse getSchema(@PathVariable String id) {
        Document s = authoring.activeSchema(id);
        if (s == null) throw new NotFoundException("no such schema: " + id);
        List<SchemaFieldResponse> fields = new ArrayList<>();
        for (Document f : s.getList("fields", Document.class)) {
            fields.add(new SchemaFieldResponse(f.getString("key"),
                    Boolean.TRUE.equals(f.getBoolean("required"))));
        }
        return new SchemaResponse(s.getString("schema_id"), s.getInteger("version"),
                s.getString("scope"), fields, s.getString("status"));
    }
}
