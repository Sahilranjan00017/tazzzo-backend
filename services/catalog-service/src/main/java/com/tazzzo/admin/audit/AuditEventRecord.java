package com.tazzzo.admin.audit;

import com.tazzzo.common.audit.ActorType;

import java.time.Instant;

/**
 * One audit event as the read API exposes it: an ALLOWLISTED projection. The ledger's free-form {@code detail} map is
 * deliberately not read (it may hold arbitrary domain values), and nothing here is a token, header, session, claim, email
 * or stack. {@code credentialId} is the persisted, non-secret credential label, or {@code null} when none was recorded:
 * it is never manufactured. {@code requestId} is {@code null} only for SYSTEM work, which has no request.
 */
public record AuditEventRecord(String id, Instant occurredAt, String action, String targetType, String targetId,
                               ActorType actorType, String actorId, String credentialId, String requestId) {
}
