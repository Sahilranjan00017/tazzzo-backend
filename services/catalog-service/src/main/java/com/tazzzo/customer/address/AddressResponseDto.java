package com.tazzzo.customer.address;

/**
 * PR-12B — the public address projection. NEVER exposes {@code fulfillmentLocationId},
 * {@code serviceAreaId}, {@code warehouseId}, Mongo internals, or an internal route/cache key.
 * {@code serviceability} is computed dynamically on every read/response — it is never persisted
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
