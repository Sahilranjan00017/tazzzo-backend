package com.tazzzo.catalog.datastore;

/**
 * Which identity a process is meant to run as (DB-4). The runtime application and the migration job use SEPARATE
 * database identities; each profile pins exactly what that identity may hold.
 * <ul>
 *   <li>{@link #RUNTIME}: the normally started application (mode {@code VERIFY}). Reads and writes application
 *       data, reads the migration history, and holds NO schema authority, no write on the migration bookkeeping and
 *       nothing outside the application database.</li>
 *   <li>{@link #MIGRATION_READ}: a {@code DRY_RUN} job. Needs read access only, and may run under the read-only
 *       identity or under the apply identity.</li>
 *   <li>{@link #MIGRATION_APPLY}: an {@code APPLY} job. Schema authority and the narrow data writes migrations make.</li>
 * </ul>
 */
public enum PrivilegeProfile {
    RUNTIME,
    MIGRATION_READ,
    MIGRATION_APPLY
}
