package com.tazzzo.commerce.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tazzzo.commerce.contract.ImageRole;

/** A product image reference (CDN URL only). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductImageDto(String url, ImageRole role, int order,
                              String alt, Integer width, Integer height) { }
