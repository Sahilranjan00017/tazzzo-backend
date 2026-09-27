package com.tazzzo.auth.otp;

/**
 * PR-11B durability fix — thrown from INSIDE a {@code Tx.run} callback to force the whole Mongo
 * transaction to abort when an expected write's result reveals an invariant violation (e.g. an
 * earlier write in the SAME transaction succeeded but a later one, expected to apply, did not).
 * Never escapes {@code OtpService} to a caller — always caught immediately after {@code tx.run}
 * returns and mapped to a public {@link OtpFailure}. Package-private: purely an internal control-flow
 * signal, not part of any public contract.
 */
final class OtpTransactionAbortedException extends RuntimeException {

    OtpTransactionAbortedException(String message) {
        super(message);
    }
}
