package com.tazzzo.catalog.tx;

/** Carries its public API code as a FIELD — never sniffed from the message. */
public class EvidenceContractException extends RuntimeException {

    public final String code;

    public EvidenceContractException(String code, String message) {
        super(message);
        this.code = code;
    }
}
