package com.tazzzo.catalog.migration;

import java.util.Set;

/**
 * Refuses to mutate an ambiguous or disallowed target. Rules:
 * <ul>
 *   <li>the environment must be named and one of local/test/dev/staging/production;</li>
 *   <li>the database name must be known;</li>
 *   <li>{@code APPLY_ON_STARTUP} and {@code LEGACY} mutation are only for local/test/dev, never staging/production;</li>
 *   <li>{@code APPLY} outside local/test/dev needs a two-key confirmation: the confirmed database AND environment
 *       must equal the actual target (a typed confirmation, so a mis-pointed URI cannot be migrated by accident).</li>
 * </ul>
 * The environment label is metadata, not a boundary: the self-serve treatment of local/test/dev (startup mutation,
 * APPLY without confirmation) applies only when every target host is a loopback literal. A remote, private-network
 * or unknown target under a self-serve label is treated like staging/production: no startup/legacy mutation, and
 * APPLY needs the two-key confirmation. (The datastore verifier independently enforces the connection contract and
 * the privilege profile for such a target.)
 */
public final class TargetGuard {

    public static final Set<String> KNOWN_ENVIRONMENTS = Set.of("local", "test", "dev", "staging", "production");
    public static final Set<String> SELF_SERVE_ENVIRONMENTS = Set.of("local", "test", "dev");

    private TargetGuard() { }

    /** The single authoritative decision, made once when the target is built (loopback-only hosts, no proxy). */
    static boolean isLocalTarget(MigrationTarget target) {
        return target.local();
    }

    public static void requireMutationAllowed(MigrationMode mode, MigrationTarget target,
                                              String confirmDatabase, String confirmEnvironment) {
        String env = target.environment();
        if (env == null || env.isBlank()) {
            throw new TargetRefusedException("refusing to mutate: the environment is not identified "
                    + "(set tazzzo.migration.environment to one of " + KNOWN_ENVIRONMENTS + ")");
        }
        if (!KNOWN_ENVIRONMENTS.contains(env)) {
            throw new TargetRefusedException("refusing to mutate: unknown environment '" + env + "' (expected one of "
                    + KNOWN_ENVIRONMENTS + ")");
        }
        if (target.database() == null || target.database().isBlank()) {
            throw new TargetRefusedException("refusing to mutate: the target database is not identified");
        }
        boolean selfServe = SELF_SERVE_ENVIRONMENTS.contains(env) && isLocalTarget(target);
        if ((mode == MigrationMode.APPLY_ON_STARTUP || mode == MigrationMode.LEGACY) && !selfServe) {
            throw new TargetRefusedException("refusing to mutate at application startup in environment '" + env
                    + "' or against a non-loopback datastore: startup mutation is only allowed in " + SELF_SERVE_ENVIRONMENTS
                    + " on a loopback server; run the controlled migration job (mode=APPLY) instead");
        }
        if (mode == MigrationMode.APPLY && !selfServe) {
            if (!target.database().equals(confirmDatabase) || !env.equals(confirmEnvironment)) {
                throw new TargetRefusedException("refusing to mutate '" + env + "': two-key confirmation required — set "
                        + "tazzzo.migration.confirm-database to the exact database name and "
                        + "tazzzo.migration.confirm-environment to the exact environment");
            }
        }
    }
}
