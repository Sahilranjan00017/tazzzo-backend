package com.tazzzo.customer.address;

/**
 * PR-12B — {@code serviceable=null} means UNKNOWN (the serviceability dependency could not
 * currently be evaluated), deliberately distinct from {@code false} (a definite, valid answer:
 * this location is outside coverage). Never collapsed into each other.
 */
public record AddressServiceabilityDto(Boolean serviceable) {

    static AddressServiceabilityDto from(AddressServiceabilityEvaluator.Result result) {
        return switch (result) {
            case SERVICEABLE -> new AddressServiceabilityDto(Boolean.TRUE);
            case UNSERVICEABLE -> new AddressServiceabilityDto(Boolean.FALSE);
            case UNKNOWN -> new AddressServiceabilityDto(null);
        };
    }
}
