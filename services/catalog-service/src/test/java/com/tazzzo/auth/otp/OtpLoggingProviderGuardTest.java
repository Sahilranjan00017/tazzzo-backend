package com.tazzzo.auth.otp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The dev-only LOGGING OTP provider can never be wired where a real customer would wait for a code. */
class OtpLoggingProviderGuardTest {

    static OtpAuthProperties mode(String m) {
        OtpAuthProperties p = new OtpAuthProperties();
        p.setProviderMode(m);
        return p;
    }

    @Test
    void logging_is_allowed_only_in_unset_local_test_or_dev() {
        OtpAuthConfig config = new OtpAuthConfig();
        for (String env : new String[]{null, "", "local", "test", "dev", " dev "}) {
            assertThat(config.otpDeliveryProvider(mode("LOGGING"), env)).as(String.valueOf(env)).isInstanceOf(LoggingOtpDeliveryProvider.class);
        }
        for (String env : new String[]{"staging", "production", "prod", "Production", "qa"}) {
            assertThatThrownBy(() -> config.otpDeliveryProvider(mode("LOGGING"), env)).as(env)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("refused");
        }
        assertThat(config.otpDeliveryProvider(mode(null), "production")).isNull();
        assertThat(config.otpDeliveryProvider(mode(""), "production")).isNull();
    }
}
