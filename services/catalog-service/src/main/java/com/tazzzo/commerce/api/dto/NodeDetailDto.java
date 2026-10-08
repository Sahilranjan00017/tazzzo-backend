package com.tazzzo.commerce.api.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * One consumer-visible taxonomy node by id (/v1 contract). The node fields are unwrapped so the JSON
 * is flat (matching the OpenAPI {@code allOf(Node, ...)} composition), the same way
 * {@link ProductDetailDto} flattens a card: a node read by id and a node in a listing never drift.
 */
public record NodeDetailDto(
        @JsonUnwrapped NodeDto node,
        String resolvedReleaseId,
        String requestId
) { }
