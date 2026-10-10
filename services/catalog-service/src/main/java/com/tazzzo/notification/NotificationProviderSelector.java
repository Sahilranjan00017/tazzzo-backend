package com.tazzzo.notification;

import java.util.Set;

/**
 * Chooses the {@link NotificationSender} from {@code tazzzo.notifications.provider}. Unset/blank/{@code disabled} keeps
 * today's behaviour (no delivery; enabling dispatch is then a startup failure). {@code sandbox} (exact lowercase; {@code SANDBOX} is rejected) is allowed only where
 * {@code tazzzo.migration.environment} is unset, local, test or dev (the same rule as the OTP LOGGING provider). Anything
 * else is a startup failure, never a silent fallback.
 */
final class NotificationProviderSelector {

    static final Set<String> DEV_ENVIRONMENTS = Set.of("", "local", "test", "dev");

    private NotificationProviderSelector() { }

    static NotificationSender select(String provider, String environment) {
        String p = provider == null ? "" : provider.trim();   // exact lowercase values only, like the OTP provider-mode
        String env = environment == null ? "" : environment.trim();
        return switch (p) {
            case "", "disabled" -> new DisabledNotificationSender();
            case "sandbox" -> {
                if (!DEV_ENVIRONMENTS.contains(env)) {
                    throw new IllegalStateException("tazzzo.notifications.provider=sandbox is refused in environment '" + env
                            + "' (allowed only when tazzzo.migration.environment is unset, local, test or dev)");
                }
                yield new SandboxNotificationSender();
            }
            default -> throw new IllegalStateException("tazzzo.notifications.provider must be 'disabled' or 'sandbox', was: '"
                    + provider + "'");
        };
    }
}
