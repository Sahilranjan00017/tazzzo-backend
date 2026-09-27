package com.tazzzo.commerce.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result of resolving a location to a serviceable area + ETA range.
 *
 * <p>{@code serviceAreaVersion} is {@code long} end-to-end (PR-10B final review #4): the domain
 * {@code ServiceArea.version} is an authoritative, monotonic {@code long} and must not be narrowed
 * to {@code int32} at the public edge.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServiceabilityResponseDto(boolean serviceable, String serviceAreaId,
                                        Long serviceAreaVersion, Integer etaMinutesMin,
                                        Integer etaMinutesMax, String requestId) { }
