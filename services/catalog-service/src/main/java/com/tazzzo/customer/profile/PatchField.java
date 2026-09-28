package com.tazzzo.customer.profile;

import java.util.Objects;

/**
 * PR-12A — an explicit three-state PATCH field: the wire distinguishes a field being OMITTED from
 * the body entirely, versus PRESENT with an explicit {@code null} (clear the value), versus
 * PRESENT with a real value. A plain nullable Java field cannot represent this (a plain
 * {@code String} collapses "omitted" and "explicit null" into the same {@code null}), so this small
 * wrapper is deliberately used instead of a generic Jackson nullable-deserialization dependency.
 */
public final class PatchField<T> {

    private static final PatchField<?> ABSENT = new PatchField<>(false, null);

    private final boolean present;
    private final T value;

    private PatchField(boolean present, T value) {
        this.present = present;
        this.value = value;
    }

    @SuppressWarnings("unchecked")
    public static <T> PatchField<T> absent() {
        return (PatchField<T>) ABSENT;
    }

    public static <T> PatchField<T> of(T value) {
        return new PatchField<>(true, value);
    }

    public boolean isPresent() {
        return present;
    }

    public T value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PatchField<?> other)) return false;
        return present == other.present && Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(present, value);
    }
}
