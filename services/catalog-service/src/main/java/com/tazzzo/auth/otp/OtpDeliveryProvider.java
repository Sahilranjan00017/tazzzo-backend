package com.tazzzo.auth.otp;

import java.time.Duration;

/**
 * PR-11B — the provider abstraction for OTP delivery. Domain lifecycle code (OtpService) never
 * couples to a specific SMS/WhatsApp vendor; a production Twilio/MSG91/AWS SNS/WhatsApp integration
 * is explicitly OUT OF SCOPE for this PR and is a future adapter behind this same interface.
 *
 * <p>There is deliberately no default production implementation: a missing bean means the OTP
 * request endpoint fails closed with 503, never a silent discard (see {@code OtpAuthConfig}).
 */
public interface OtpDeliveryProvider {

    /**
     * @throws OtpFailure UNAVAILABLE — delivery could not be confirmed (timeout, provider error,
     *         etc). The caller must not leave a challenge in a usable state on failure.
     */
    void sendLoginOtp(Phone phone, String otp, Duration expiresIn);
}
