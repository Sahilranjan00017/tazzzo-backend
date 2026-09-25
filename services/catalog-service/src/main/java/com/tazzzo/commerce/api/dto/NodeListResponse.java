package com.tazzzo.commerce.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** ROOT / CHILDREN listing envelope (frozen /v1 contract). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NodeListResponse(String resolvedReleaseId, List<NodeDto> items, String requestId) { }
