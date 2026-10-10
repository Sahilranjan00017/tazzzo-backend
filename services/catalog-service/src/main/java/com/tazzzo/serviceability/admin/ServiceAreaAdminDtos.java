package com.tazzzo.serviceability.admin;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Wire shapes of the INTERNAL service-area admin API. Fulfilment location ids appear here and nowhere public. */
final class ServiceAreaAdminDtos {

    private ServiceAreaAdminDtos() { }

    record RouteDto(String fulfillmentLocationId, Integer priority, Boolean active) { }

    /** {@code expectedVersion} absent = create (version 1); present = compare-and-set update. */
    record UpsertRequest(String serviceAreaId, List<RouteDto> routes, Long expectedVersion) { }

    @io.swagger.v3.oas.annotations.media.Schema(name = "ServiceAreaVersionRequest")
    record VersionRequest(Long expectedVersion) { }

    record AreaResponse(String pincode, String serviceAreaId, boolean active, long version, List<RouteDto> routes) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PageResponse(List<AreaResponse> items, String nextCursor) { }
}
