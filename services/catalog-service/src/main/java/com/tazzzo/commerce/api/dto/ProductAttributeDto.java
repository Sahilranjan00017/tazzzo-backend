package com.tazzzo.commerce.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A projected consumer attribute. {@code value} is the governed attribute value (any JSON type). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductAttributeDto(String key, String label, Object value, String unit) { }
