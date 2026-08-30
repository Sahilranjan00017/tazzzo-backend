package com.tazzzo.catalog.tx;

/**
 * D-1b — an update tried to mutate a field the contract declares immutable after creation.
 * Maps to 422 IMMUTABLE_FIELD at the API.
 */
public class ImmutableFieldException extends RuntimeException {
    public ImmutableFieldException(String message) {
        super(message);
    }
}
