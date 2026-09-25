package com.tazzzo.commerce.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Result of resolving a location to a serviceable area + ETA range. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServiceabilityResponseDto(boolean serviceable, String serviceAreaId,
                                        Integer serviceAreaVersion, Integer etaMinutesMin,
                                        Integer etaMinutesMax, String requestId) { }
