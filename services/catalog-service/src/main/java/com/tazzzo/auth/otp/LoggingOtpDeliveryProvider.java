package com.tazzzo.auth.otp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * PR-11B — a development/local-only provider that "delivers" an OTP by logging a BOUNDED event —
 * never the plaintext OTP, never the full phone number. Activated only when
 * {@code tazzzo.customer-auth.otp.provider-mode=LOGGING} is explicitly set (see
 * {@link OtpAuthConfig}); there is no default production provider.
 *
 * <p>This is NOT how a real OTP reaches a real phone. It exists so the request/verify lifecycle can
 * be exercised end-to-end in a local/dev environment without a paid SMS vendor. Test code that needs
 * to observe the plaintext OTP uses a dedicated test-only fake instead of this class.
 */
public class LoggingOtpDeliveryProvider implements OtpDeliveryProvider {

    private static final Logger log = LoggerFactory.getLogger(LoggingOtpDeliveryProvider.class);

    @Override
    public void sendLoginOtp(Phone phone, String otp, Duration expiresIn) {
        // Bounded event only: masked phone, no OTP value, no exact expiry timestamp.
        log.info("otp_delivered_dev provider=LOGGING phone={} expires_in_seconds={}",
                phone.masked(), expiresIn.getSeconds());
    }
}
