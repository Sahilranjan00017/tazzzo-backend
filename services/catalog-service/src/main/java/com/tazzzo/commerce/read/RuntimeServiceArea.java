package com.tazzzo.commerce.read;

/**
 * INTERNAL runtime service-area summary (PR-08, STEP 17): the ONLY location facts a page result
 * may carry — public serviceAreaId + the serviceable flag. NEVER the internal
 * fulfillmentLocationId (that value stays inside the composer's local scope). The future API
 * layer maps this to the public {@code ServiceAreaSummaryDto}.
 */
public record RuntimeServiceArea(String serviceAreaId, boolean serviceable) {

    public RuntimeServiceArea {
        if (serviceAreaId == null || serviceAreaId.isBlank()) {
            throw new IllegalArgumentException("serviceAreaId required");
        }
    }
}
