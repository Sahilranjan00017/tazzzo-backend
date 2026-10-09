package com.tazzzo.media;

import java.util.Optional;

/** The default: no object storage configured. */
public final class DisabledMediaStorage implements MediaStorage {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public UploadTarget createUpload(String assetKey, String contentType, long sizeBytes) {
        throw new IllegalStateException("no media storage configured");
    }

    @Override
    public Optional<StoredObject> inspect(String assetKey) {
        throw new IllegalStateException("no media storage configured");
    }
}
