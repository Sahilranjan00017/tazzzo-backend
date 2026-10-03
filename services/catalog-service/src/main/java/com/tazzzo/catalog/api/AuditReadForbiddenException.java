package com.tazzzo.catalog.api;

/** An authenticated admin without the audit-read permission (403 {@code FORBIDDEN}, fixed message). */
public class AuditReadForbiddenException extends RuntimeException {

    public AuditReadForbiddenException() {
        super("audit read not granted");
    }
}
