package com.tazzzo.catalog.consumer;

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

    /** {@code resolved_release_id} is echoed per TR-1: the response says which release answered it. */
    public record RootResponse(@JsonProperty("resolved_release_id") String resolvedReleaseId,
                               List<ConsumerNode> items) { }

    /**
     * ERR-1 — FLAT, deliberately unlike the CMS envelope's nested {@code {"error": {…}}}. The two
     * surfaces are separate contracts under Q4-c, and the consumer one must not inherit a CMS
     * transport decision merely because an implementation already existed.
     */
    public record ConsumerError(String code, String message,
                                @JsonProperty("request_id") String requestId) { }
}
