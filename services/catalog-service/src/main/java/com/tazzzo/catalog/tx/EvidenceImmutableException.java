package com.tazzzo.catalog.tx;

public class EvidenceImmutableException extends RuntimeException {
    public EvidenceImmutableException(String m) { super("evidence metadata is immutable: " + m); }
}
