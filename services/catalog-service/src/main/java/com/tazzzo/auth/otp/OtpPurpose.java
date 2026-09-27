package com.tazzzo.auth.otp;

/**
 * PR-11B — the closed vocabulary of reasons an OTP challenge may be issued for. Deliberately NOT an
 * arbitrary String: a challenge/grant issued for one purpose must never be usable for another, and a
 * typed enum makes "issued for LOGIN, consumed as something else" a compile-time impossibility to
 * introduce by accident. Future purposes (e.g. phone-change verification) are added here
 * deliberately, never inferred.
 */
public enum OtpPurpose {
    LOGIN
}
