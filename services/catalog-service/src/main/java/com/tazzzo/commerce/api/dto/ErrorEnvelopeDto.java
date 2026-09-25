package com.tazzzo.commerce.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tazzzo.commerce.contract.PublicErrorCode;
import java.util.Map;

/** Flat public error envelope (ADR-010). Message is safe/generic; no internals exposed. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorEnvelopeDto(
        PublicErrorCode code,
        String message,
        String requestId,
        boolean retryable,
        Integer retryAfterSeconds,
        Map<String, Object> details
) { }
