package com.tazzzo.admin.audit;

/**
 * A persisted ledger row that cannot be projected (missing timestamp, non-ObjectId id, malformed actor). A server-side
 * data fault, never a client error: it surfaces as the generic 500 and the message names no row content.
 */
public class AuditRecordCorrupt extends RuntimeException {

    AuditRecordCorrupt(String collection) {
        super("audit ledger row could not be projected: " + collection);
    }
}
