package com.tazzzo.catalog.tx;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.WritePath;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;
import java.util.Set;

/**
 * THE owner of product lifecycle transitions (spec Part 5B). Previously only draft->active
 * existed (misplaced in BundleService) and merge transitions lived in MergeService; the
 * approved state machine also requires discontinue, revive and archive.
 *
 * Legal:   draft->active · active->discontinued · discontinued->active (revive)
 *          discontinued->archived
 * Illegal: anything out of merged/archived (terminal) · any transition into or out of
 *          `merging` (MergeService owns that exclusively) · skipping states.
 * Revive rule (contract): same formulation_version + intact GTIN binding reactivates the SAME
 * product id; a changed formulation requires a NEW product with `supersedes` — enforced by
 * refusing revive when the caller declares a different formulation.
 */
@Service
public class ProductLifecycleService {

    private static final Map<String, Set<String>> LEGAL = Map.of(
            "draft", Set.of("active"),
            "active", Set.of("discontinued"),
            "discontinued", Set.of("active", "archived"));

    private final Tx tx;
    private final WritePath writePath;

    public ProductLifecycleService(Tx tx, WritePath writePath) {
        this.tx = tx;
        this.writePath = writePath;
    }

    public void activate(String productId, int expectedVersion) {
        transition(productId, expectedVersion, "active", "PRODUCT_ACTIVATED", null);
    }

    public void discontinue(String productId, int expectedVersion, String reason) {
        transition(productId, expectedVersion, "discontinued", "PRODUCT_DISCONTINUED", reason);
    }

    /** Reactivate a discontinued product. Refused if the formulation has changed. */
    public void revive(String productId, int expectedVersion, Integer declaredFormulationVersion) {
        tx.run(session -> {
            Document p = require(session, productId);
            assertLegal(p.getString("lifecycle"), "active");
            Integer current = p.getInteger("formulation_version");
            if (declaredFormulationVersion != null && current != null
                    && !declaredFormulationVersion.equals(current)) {
                throw new ProductStateException("formulation changed (" + current + " -> "
                        + declaredFormulationVersion + "): mint a NEW product with supersedes");
            }
            writePath.casUpdateWithEvent(session, "products", productId, expectedVersion,
                    Updates.combine(Updates.set("lifecycle", "active"),
                            Updates.set("updated_at", new Date()), Updates.inc("version", 1)),
                    new EventPayload("PRODUCT_REVIVED", productId, Map.of()));
        });
    }

    /** Terminal. Nothing leaves `archived`. */
    public void archive(String productId, int expectedVersion) {
        transition(productId, expectedVersion, "archived", "PRODUCT_ARCHIVED", null);
    }

    private void transition(String productId, int expectedVersion, String target,
                            String eventType, String reason) {
        tx.run(session -> {
            Document p = require(session, productId);
            assertLegal(p.getString("lifecycle"), target);
            writePath.casUpdateWithEvent(session, "products", productId, expectedVersion,
                    Updates.combine(Updates.set("lifecycle", target),
                            Updates.set("updated_at", new Date()), Updates.inc("version", 1)),
                    new EventPayload(eventType, productId,
                            reason == null ? Map.of() : Map.of("reason", reason)));
        });
    }

    private void assertLegal(String from, String to) {
        if (!LEGAL.getOrDefault(from, Set.of()).contains(to)) {
            throw new ProductStateException("illegal lifecycle transition " + from + " -> " + to);
        }
    }

    private Document require(com.mongodb.client.ClientSession session, String productId) {
        Document p = writePath.database().getCollection("products")
                .find(session, Filters.eq("_id", productId)).first();
        if (p == null) throw new ProductNotFoundException(productId);
        return p;
    }
}
