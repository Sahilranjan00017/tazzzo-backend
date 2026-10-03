package com.tazzzo.admin.audit;

/**
 * An audit-read request the API refuses as malformed (400 {@code MALFORMED_REQUEST} through the existing
 * {@link IllegalArgumentException} mapping). Messages are FIXED text naming a parameter, never echoing a supplied value.
 */
public class AuditQueryRejected extends IllegalArgumentException {

    public AuditQueryRejected(String message) {
        super(message);
    }
}
