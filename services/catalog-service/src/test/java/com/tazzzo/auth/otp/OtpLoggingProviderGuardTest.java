package com.tazzzo.auth.otp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The dev-only LOGGING OTP provider can never be wired where a real customer would wait for a code. */
class OtpLoggingProviderGuardTest {

    static final org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> NO_REGISTRY =
            new org.springframework.beans.factory.support.StaticListableBeanFactory()
                    .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class);

    static OtpAuthProperties mode(String m) {
        OtpAuthProperties p = new OtpAuthProperties();
        p.setProviderMode(m);
        return p;
    }

    @Test
    void logging_is_allowed_only_in_unset_local_test_or_dev() {
        OtpAuthConfig config = new OtpAuthConfig();
        for (String env : new String[]{null, "", "local", "test", "dev", " dev "}) {
            assertThat(config.otpDeliveryProvider(mode("LOGGING"), NO_REGISTRY, env)).as(String.valueOf(env)).isInstanceOf(LoggingOtpDeliveryProvider.class);
        }
        for (String env : new String[]{"staging", "production", "prod", "Production", "qa"}) {
            assertThatThrownBy(() -> config.otpDeliveryProvider(mode("LOGGING"), NO_REGISTRY, env)).as(env)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("refused");
        }
        assertThat(config.otpDeliveryProvider(mode(null), NO_REGISTRY, "production")).isNull();
        assertThat(config.otpDeliveryProvider(mode(""), NO_REGISTRY, "production")).isNull();
    }
}
