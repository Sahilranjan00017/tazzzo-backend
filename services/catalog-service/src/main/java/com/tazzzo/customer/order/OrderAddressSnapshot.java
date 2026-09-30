package com.tazzzo.customer.order;

/**
 * PR-14B — the immutable delivery-address snapshot frozen from the version-validated
 * {@code customer_addresses} document at the moment the Order transaction committed. A later edit
 * to the source address (even a full delete) never changes this snapshot — the same "frozen at
 * commit, never re-read" discipline {@link OrderLine} follows for product display data.
 *
 * <p>Fields mirror {@code AddressRepository}'s stored document exactly — no invented fields.
 *
 * <p><b>Coordinates follow the Address domain's own contract</b> (the authority): the pair is either
 * BOTH absent ({@code null}, a valid coordinate-less address) or BOTH present, finite and in range
 * (latitude in [-90, 90], longitude in [-180, 180]). Exactly one present, out-of-range or non-finite is
 * corrupt/inconsistent state and fails LOUD — never defaulted ({@code 0.0}, {@code NaN}, a city centre)
 * and never geocoded. They are carried for INTERNAL order history/fulfilment routing only and are
 * NEVER exposed publicly ({@code CustomerOrderDto} excludes them). Serviceability remains PIN-based and
 * does not depend on coordinates.
 */
public record OrderAddressSnapshot(String label, String recipientName, String recipientPhone, String addressLine1,
                                   String addressLine2, String landmark, String city, String state,
                                   String postalCode, Double latitude, Double longitude) {

    public OrderAddressSnapshot {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label required");
        }
        if (recipientName == null || recipientName.isBlank()) {
            throw new IllegalArgumentException("recipientName required");
        }
        if (recipientPhone == null || recipientPhone.isBlank()) {
            throw new IllegalArgumentException("recipientPhone required");
        }
        if (addressLine1 == null || addressLine1.isBlank()) {
            throw new IllegalArgumentException("addressLine1 required");
        }
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("city required");
        }
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("state required");
        }
        if (postalCode == null || postalCode.isBlank()) {
            throw new IllegalArgumentException("postalCode required");
        }
        // addressLine2/landmark are nullable, mirroring the source Address document's own
        // nullability -- this snapshot invents no stricter contract than its source.
        if (latitude == null != (longitude == null)) {
            throw new IllegalArgumentException("latitude and longitude must be both present or both absent");
        }
        if (latitude != null) {
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
                throw new IllegalArgumentException("coordinates must be finite");
            }
            if (latitude < -90.0 || latitude > 90.0) {
                throw new IllegalArgumentException("latitude out of range: " + latitude);
            }
            if (longitude < -180.0 || longitude > 180.0) {
                throw new IllegalArgumentException("longitude out of range: " + longitude);
            }
        }
    }
}
