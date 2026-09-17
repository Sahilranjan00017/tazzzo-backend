package com.tazzzo.catalog.consumer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The consumer transport shapes (CAT-NODE-1, ERR-1). Records only — no logic, no defaults that
 * could become an accidental contract.
 */
public final class ConsumerDtos {

    private ConsumerDtos() {
    }

    /**
     * CAT-NODE-1. A snapshot row carries {@code status}, {@code parent_id},
     * {@code attribute_schema_id}, {@code branch_status}, {@code origin} and
     * {@code created_in_version} — all internal. None of them appear here. {@code nodeType} is
     * deliberately absent too: ROOT holds only super-categories, so it would carry no information,
     * and a public contract grows when a real requirement exists, never speculatively.
     */
    public record ConsumerNode(String id, String name) { }

    /**
     * The one listing shape ROOT-1 and CHILD-1 share: {@code resolved_release_id} is echoed per
     * TR-1 (the response says which release answered it) and {@code items} is the TR-3-ordered,
     * hide-empty list of immediate consumer-visible nodes — empty for a visible vertical.
     */
    public record NodeListResponse(@JsonProperty("resolved_release_id") String resolvedReleaseId,
                               List<ConsumerNode> items) { }

    /**
     * LIST-1 / PAGE-SIZE-6. {@code resolved_release_id} scopes the whole request (TR-1); each item
     * carries its OWN {@code projectionVersion} (RP-6c) — there is no envelope-level projection
     * version because a category listing spans verticals. {@code next_cursor} is OMITTED, not
     * null, when no continuation exists. NOT present, by construction: total, total_count, page
     * number, taxonomy path, price, inventory, seller, ranking.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductListResponse(@JsonProperty("resolved_release_id") String resolvedReleaseId,
                                      List<ConsumerProductResponse> items,
                                      @JsonProperty("next_cursor") String nextCursor) { }

    /**
     * PDP-1 / PDP-SHAPE-1 (envelope A). {@code resolved_release_id} scopes the request (TR-1) and
     * sits on the envelope exactly as on every other consumer response; {@code item} is EXACTLY the
     * {@link ConsumerProductResponse} a LIST page carries — one DTO for one product (RP-1), so a
     * detail and a list item can never drift apart.
     */
    public record ProductDetailResponse(@JsonProperty("resolved_release_id") String resolvedReleaseId,
                                        ConsumerProductResponse item) { }

    /**
     * ERR-1 — FLAT, deliberately unlike the CMS envelope's nested {@code {"error": {…}}}. The two
     * surfaces are separate contracts under Q4-c, and the consumer one must not inherit a CMS
     * transport decision merely because an implementation already existed.
     */
    public record ConsumerError(String code, String message,
                                @JsonProperty("request_id") String requestId) { }
}
