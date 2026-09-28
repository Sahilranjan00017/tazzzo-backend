package com.tazzzo.customer.address;

/**
 * PR-12B — POST /v1/customer/addresses body. No client-supplied addressId/customerId/version/
 * createdAt/updatedAt/fulfillmentLocationId/serviceAreaId -- none of those fields exist on this DTO
 * at all.
 */
public record AddressCreateRequestDto(String label, String recipientName, String recipientPhone,
                                      String addressLine1, String addressLine2, String landmark, String city,
                                      String state, String postalCode, Double latitude, Double longitude) {
}
