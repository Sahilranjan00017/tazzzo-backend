package com.tazzzo.commerce.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Legally required product information (Indian grocery). All optional at the type level. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LegalInformationDto(String manufacturer, String countryOfOrigin, String netQuantity,
                                  String fssai, String importer) { }
