package com.tazzzo.common;

import java.util.Set;

/**
 * The single "is this a dev-like environment" rule shared by guards that refuse dev-only behaviour elsewhere: the
 * {@code tazzzo.migration.environment} must be unset, local, test or dev (the same rule as the OTP LOGGING provider and
 * the notification sandbox).
 */
public final class DevEnvironments {

    public static final Set<String> ALLOWED = Set.of("", "local", "test", "dev");

    private DevEnvironments() { }

    public static boolean isDevLike(String environment) {
        return ALLOWED.contains(environment == null ? "" : environment.trim());
    }
}
