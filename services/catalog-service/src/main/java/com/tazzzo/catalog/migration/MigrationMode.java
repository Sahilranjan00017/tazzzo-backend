package com.tazzzo.catalog.migration;

/**
 * How the application treats database evolution at startup (R5).
 * <ul>
 *   <li>{@link #VERIFY} — default. Read-only: refuses to serve if required migrations are not recorded as applied.</li>
 *   <li>{@link #DRY_RUN} — job mode: report what would happen; no mutation, no history/lock collections created.</li>
 *   <li>{@link #APPLY} — job mode: the controlled migration run (locked, recorded). Two-key confirmation outside local/test/dev.</li>
 *   <li>{@link #APPLY_ON_STARTUP} — convenience for local/test/dev only; refused for staging/production.</li>
 *   <li>{@link #LEGACY} — the pre-DB-3 flag-driven bootstrap/seed ({@code tazzzo.schema.*}); local/test/dev only. Used by
 *       tests. It runs {@code SchemaBootstrap.bootstrap}, which no longer creates the migration-owned indexes
 *       (the products cursor index and the two unique indexes); those come only from migrations.</li>
 * </ul>
 */
public enum MigrationMode {
    VERIFY, DRY_RUN, APPLY, APPLY_ON_STARTUP, LEGACY
}
