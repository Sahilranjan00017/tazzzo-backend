package com.tazzzo.catalog.migration;

import com.mongodb.client.MongoDatabase;

import java.util.List;

/**
 * One versioned, explicit, repeatable unit of database evolution (R5).
 *
 * <p>Contract every implementation must honour:
 * <ul>
 *   <li><b>stable id</b> — sortable, never reused or renamed (history is keyed by it);</li>
 *   <li><b>preflight</b> — strictly read-only; reports READY / ALREADY_SATISFIED / BLOCKED and the intended
 *       operations; must work against an empty database and must not create anything;</li>
 *   <li><b>apply</b> — idempotent and safe to retry after a partial failure; must re-inspect state rather
 *       than trust the preflight; must never delete or merge business data automatically;</li>
 *   <li><b>validate</b> — post-condition check after apply; an empty list means success;</li>
 *   <li><b>definition</b> — a stable description of exactly what the migration does; its SHA-256 is recorded,
 *       so editing an already-applied migration is detected instead of silently ignored.</li>
 * </ul>
 */
public interface Migration {

    String id();

    String description();

    MigrationKind kind();

    /** Collections this migration reads or changes (informational; recorded in reports). */
    List<String> collections();

    /** Stable text describing exactly what this migration does; feeds {@link #checksum()}. */
    String definition();

    /** Disabled migrations are registered but never run unless explicitly enabled by configuration. */
    default boolean enabledByDefault() {
        return true;
    }

    /** DATA migrations rewrite persisted data and therefore need explicit per-id approval. */
    default boolean requiresApproval() {
        return kind() == MigrationKind.DATA;
    }

    default String checksum() {
        return Checksums.sha256(id() + "\n" + kind() + "\n" + String.join(",", collections()) + "\n" + definition());
    }

    Preflight preflight(MongoDatabase db);

    ApplyResult apply(MongoDatabase db);

    /** Post-validation problems; empty means the target state holds. */
    List<String> validate(MongoDatabase db);
}
