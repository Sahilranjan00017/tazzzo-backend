package com.tazzzo.catalog.tx;

public class EvidenceNotFoundException extends RuntimeException {
    public EvidenceNotFoundException(String id) { super("no such evidence: " + id); }
}
