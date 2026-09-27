package com.tazzzo.auth;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

/** Test-only {@link ObjectProvider} returning a fixed value (possibly {@code null}) — mirrors
 *  {@code com.tazzzo.auth.otp.FixedObjectProvider}. */
final class FixedObjectProvider<T> implements ObjectProvider<T> {

    private final T value;

    FixedObjectProvider(T value) {
        this.value = value;
    }

    @Override
    public T getObject() throws BeansException {
        if (value == null) {
            throw new NoSuchBeanDefinitionException(Object.class, "fixed test provider has no value");
        }
        return value;
    }

    @Override
    public T getObject(Object... args) throws BeansException {
        return getObject();
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
