package com.tazzzo.catalog.api;

import com.tazzzo.catalog.api.ApiDtos.*;
import com.tazzzo.catalog.schema.TaxonomyService;
import com.tazzzo.catalog.tx.ProductQueryService;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import org.bson.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/** Thin transport over TaxonomyChangeService/TaxonomyService. No tree rules live here. */
@RestController
@RequestMapping("/api/v1/taxonomy")
public class TaxonomyController {

    private final TaxonomyChangeService changes;
    private final TaxonomyService taxonomy;
    private final ProductQueryService queries;

    public TaxonomyController(TaxonomyChangeService changes, TaxonomyService taxonomy, ProductQueryService queries) {
        this.changes = changes;
        this.taxonomy = taxonomy;
        this.queries = queries;
    }

    @PostMapping("/releases")
    public ResponseEntity<IdResponse> openRelease(@RequestBody OpenReleaseRequest body) {
        changes.openRelease(body.releaseId(), body.basedOn());
        return ResponseEntity.status(HttpStatus.CREATED).body(new IdResponse(body.releaseId(), null));
    }

    /** Publishing is idempotent-by-state: freeze -> snapshot -> activate; safe to retry. */
    @PostMapping("/releases/{id}/publish")
    public IdResponse publishRelease(@PathVariable String id) {
        changes.activateRelease(id);
        return new IdResponse(id, null);
    }

    @GetMapping("/releases/{id}")
    public ReleaseResponse getRelease(@PathVariable String id) {
        Document r = queries.findRelease(id);
        if (r == null) throw new NotFoundException("no such release: " + id);
        return new ReleaseResponse(r.getString("_id"), r.getString("status"), r.getString("based_on"));
    }

    @PostMapping("/nodes/{id}/revive")
    public NodeResponse revive(@PathVariable String id, @RequestBody RenameNodeRequest body) {
        changes.reviveNode(id, requireVersion(body.expectedVersion()));
        return node(id);
    }

    /** deprecate: the last proven change operation lacking an endpoint (review m10). */
    @PostMapping("/nodes/{id}/deprecate")
    public NodeResponse deprecate(@PathVariable String id, @RequestBody RenameNodeRequest body) {
        changes.deprecateNode(id, requireVersion(body.expectedVersion()));
        return node(id);
    }

    @PostMapping("/nodes/{id}/rename")
    public NodeResponse rename(@PathVariable String id, @RequestBody RenameNodeRequest body) {
        changes.renameNode(id, requireVersion(body.expectedVersion()), body.name());
        return node(id);
    }

    @PostMapping("/nodes/{id}/move")
    public NodeResponse move(@PathVariable String id, @RequestBody MoveNodeRequest body) {
        changes.moveNode(id, requireVersion(body.expectedVersion()), body.newParentId());
        return node(id);
    }

    @PostMapping("/nodes/{id}/merge")
    public NodeResponse merge(@PathVariable String id, @RequestBody MergeNodeRequest body) {
        changes.mergeNodes(id, requireVersion(body.expectedVersion()), body.survivorId(),
                Boolean.TRUE.equals(body.schemaReconciliationApproved()));
        return node(id);
    }

    @PostMapping("/nodes/{id}/split")
    public List<NodeResponse> split(@PathVariable String id, @RequestBody SplitNodeRequest body) {
        List<String> minted = changes.splitNode(id, requireVersion(body.expectedVersion()),
                body.childNames());
        List<NodeResponse> out = new ArrayList<>();
        for (String m : minted) out.add(node(m));
        return out;
    }

    @GetMapping("/nodes/{id}")
    public NodeResponse node(@PathVariable String id) {
        Document n = taxonomy.node(id);
        if (n == null) throw new NotFoundException("no such node: " + id);
        return new NodeResponse(n.getString("_id"), n.getString("node_type"), n.getString("name"),
                n.getString("parent_id"), n.getString("status"),
                n.getString("attribute_schema_id"), n.getInteger("version"));
    }

    @GetMapping("/nodes/{id}/path")
    public PathResponse path(@PathVariable String id) {
        if (taxonomy.node(id) == null) throw new NotFoundException("no such node: " + id);
        List<NodeResponse> nodes = new ArrayList<>();
        for (Document n : taxonomy.path(id)) {
            nodes.add(new NodeResponse(n.getString("_id"), n.getString("node_type"),
                    n.getString("name"), n.getString("parent_id"), n.getString("status"),
                    n.getString("attribute_schema_id"), n.getInteger("version")));
        }
        return new PathResponse(id, taxonomy.renderPath(id), nodes);
    }

    private int requireVersion(Integer v) {
        if (v == null) throw new IllegalArgumentException("expectedVersion is required (optimistic concurrency)");
        return v;
    }
}
