package com.tazzzo.commerce.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Which service area answered a request. Never carries an internal fulfillmentLocationId. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServiceAreaSummaryDto(String serviceAreaId, boolean serviceable) { }
