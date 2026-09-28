package com.tazzzo.customer.profile;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

/** Test-only {@link ObjectProvider} returning a fixed value (possibly {@code null}) — mirrors
 *  {@code com.tazzzo.auth.otp.FixedObjectProvider}/{@code com.tazzzo.auth.FixedObjectProvider}. */
final class FixedObjectProvider<T> implements ObjectProvider<T> {

    private final T value;

    FixedObjectProvider(T value) {
        this.value = value;
    }

    @Override
    public T getObject() throws BeansException {
        return value;
    }

    @Override
    public T getObject(Object... args) throws BeansException {
        return value;
    }

    @Override
    public T getIfAvailable() throws BeansException {
        return value;
    }

    @Override
    public T getIfUnique() throws BeansException {
        return value;
    }
}
