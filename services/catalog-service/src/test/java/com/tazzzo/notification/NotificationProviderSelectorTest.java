package com.tazzzo.notification;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationProviderSelectorTest {

    @Test
    void unset_blank_and_disabled_keep_the_non_delivering_default() {
        for (String p : new String[]{null, "", "  ", "disabled"}) {
            NotificationSender s = NotificationProviderSelector.select(p, "production");
            assertThat(s).isInstanceOf(DisabledNotificationSender.class);
            assertThat(s.delivers()).isFalse();
        }
    }

    @Test
    void sandbox_is_allowed_only_in_unset_local_test_or_dev() {
        for (String env : new String[]{null, "", "local", "test", "dev"}) {
            NotificationSender s = NotificationProviderSelector.select("sandbox", env);
            assertThat(s).isInstanceOf(SandboxNotificationSender.class);
            assertThat(s.delivers()).isTrue();
        }
        for (String env : new String[]{"staging", "production", "prod", "Production", "unknown"}) {
            assertThatThrownBy(() -> NotificationProviderSelector.select("sandbox", env))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("refused");
        }
    }

    @Test
    void the_environment_is_trimmed_and_the_provider_value_is_exact_lowercase() {
        assertThat(NotificationProviderSelector.select("sandbox", " dev ")).isInstanceOf(SandboxNotificationSender.class);
        assertThat(NotificationProviderSelector.select(" sandbox ", "dev")).isInstanceOf(SandboxNotificationSender.class);
        assertThatThrownBy(() -> NotificationProviderSelector.select("sandbox", " production "))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("refused");
        assertThatThrownBy(() -> NotificationProviderSelector.select("SANDBOX", "dev")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> NotificationProviderSelector.select("DISABLED", "dev")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void an_unknown_provider_is_a_startup_failure_not_a_fallback() {
        assertThatThrownBy(() -> NotificationProviderSelector.select("twilio", "dev"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'disabled' or 'sandbox'");
    }
}
