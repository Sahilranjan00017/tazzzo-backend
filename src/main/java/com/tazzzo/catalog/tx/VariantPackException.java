package com.tazzzo.catalog.tx;

/** F-5: variant_pack contract violation — maps to 422 VARIANT_PACK_INVALID at the API. */
public class VariantPackException extends RuntimeException {
    public VariantPackException(String message) {
        super(message);
    }
}
