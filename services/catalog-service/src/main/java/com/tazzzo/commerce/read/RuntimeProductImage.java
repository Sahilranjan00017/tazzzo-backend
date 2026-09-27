package com.tazzzo.commerce.read;

import com.tazzzo.commerce.contract.ImageRole;

import java.util.Objects;

/**
 * One resolved PDP image (PR-09). Mirrors the frozen contract mapping pre-declared on
 * {@code MediaSet} (STEP 25): {@code MediaAsset → ProductImageDto{url, role, order, alt,
 * width, height}} — carried internally with the URL ALREADY resolved (never a raw assetKey:
 * keys are storage-internal and resolution is a runtime concern).
 */
public record RuntimeProductImage(
        String url,
        ImageRole role,
        int sortOrder,
        String altText,
        Integer width,
        Integer height
) {
    public RuntimeProductImage {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url required");
        Objects.requireNonNull(role, "role required");
        if (sortOrder < 0) throw new IllegalArgumentException("sortOrder must be >= 0");
        if (role == ImageRole.PRIMARY && sortOrder != 0) {
            // frozen MediaSet invariant carried through: primary always sorts first
            throw new IllegalArgumentException("PRIMARY image must have sortOrder 0");
        }
        if (width != null && width < 1) throw new IllegalArgumentException("width must be positive");
        if (height != null && height < 1) throw new IllegalArgumentException("height must be positive");
    }
}
