package com.tazzzo.account;

/** Bounded failure reasons of the account-deletion surface; never carries customer data. */
public final class AccountDeletionFailure extends RuntimeException {

    public enum Reason { INVALID_REQUEST, UNAVAILABLE }

    private final Reason reason;

    public AccountDeletionFailure(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
