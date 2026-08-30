package com.tazzzo.catalog.tx;

/** Fail-closed taxonomy change rejection. `code` is asserted by tests (T-RULE-1). */
public class TaxonomyChangeException extends RuntimeException {

    public final String code;

    public TaxonomyChangeException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }
}
