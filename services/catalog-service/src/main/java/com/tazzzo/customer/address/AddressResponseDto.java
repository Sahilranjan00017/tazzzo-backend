package com.tazzzo.customer.address;

/**
 * PR-12B — the public address projection. NEVER exposes {@code fulfillmentLocationId} (INTERNAL —
 * the serviceability domain's own internal routing target, never a public field anywhere) or
 * {@code warehouseId}/Mongo internals/an internal route or cache key. {@code serviceAreaId} and
 * {@code serviceAreaVersion} ARE public fields in the EXISTING {@code /v1/serviceability} contract
 * ({@code PublicServiceability}) — this projection deliberately OMITS them because the
 * customer-address response is intentionally a narrower {@code serviceable}-only view, not because
 * they are secret.
 *
 * <p>{@code serviceability} is computed dynamically on every read/response — it is never persisted
 * address state (see {@link AddressServiceabilityEvaluator}).
 */
public record AddressResponseDto(String addressId, String label, String recipientName, String recipientPhone,
                                 String addressLine1, String addressLine2, String landmark, String city,
                                 String state, String postalCode, Double latitude, Double longitude,
                                 boolean isDefault, long version, AddressServiceabilityDto serviceability,
                                 String requestId) {

    static AddressResponseDto from(AddressService.AddressView view, AddressServiceabilityEvaluator.Result result,
                                   String requestId) {
        return new AddressResponseDto(view.addressId(), view.label().name(), view.recipientName(),
                view.recipientPhone(), view.addressLine1(), view.addressLine2(), view.landmark(), view.city(),
                view.state(), view.postalCode(), view.latitude(), view.longitude(), view.isDefault(),
                view.version(), AddressServiceabilityDto.from(result), requestId);
    }
}
