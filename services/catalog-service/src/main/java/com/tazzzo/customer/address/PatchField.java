package com.tazzzo.customer.address;

import java.util.Objects;

/**
 * PR-12B — an explicit three-state PATCH field: OMITTED from the body entirely, PRESENT with an
 * explicit {@code null} (clear the value), or PRESENT with a real value. Deliberately a small
 * address-owned copy of the same idea {@code customer.profile.PatchField} uses — NOT a shared
 * import, since {@code customer.address} must not depend on {@code customer.profile}.
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
