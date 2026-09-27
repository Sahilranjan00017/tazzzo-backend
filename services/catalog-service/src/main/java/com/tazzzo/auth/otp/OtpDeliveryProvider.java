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
     * @throws OtpProviderException delivery could not be confirmed (timeout, vendor error, etc) —
     *         the formal contract for this method. {@code OtpService} also defensively catches the
     *         broader {@link RuntimeException} in case an adapter throws something else, but never
     *         {@link OtpFailure} directly: this interface has no dependency on the HTTP-facing
     *         failure vocabulary. The caller (never this method) is responsible for leaving no
     *         challenge in a falsely-usable state on failure.
     */
    void sendLoginOtp(Phone phone, String otp, Duration expiresIn);
}
