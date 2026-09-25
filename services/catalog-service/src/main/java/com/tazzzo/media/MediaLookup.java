package com.tazzzo.media;

import java.util.Optional;

/**
 * Result of a media read (STEP 14). PRESENT = active canonical set (possibly with zero assets —
 * deliberately cleared); MISSING = no set exists (data-quality signal, browse uses the
 * placeholder later); INACTIVE = a set exists but is switched off.
 */
public record MediaLookup(Status status, MediaSet mediaSet) {

    public enum Status { PRESENT, MISSING, INACTIVE }

    public static MediaLookup missing() {
        return new MediaLookup(Status.MISSING, null);
    }

    public static MediaLookup of(Status status, MediaSet mediaSet) {
        return new MediaLookup(status, mediaSet);
    }

    public boolean isPresent() {
        return status == Status.PRESENT && mediaSet != null;
    }

    public Optional<MediaSet> presentSet() {
        return isPresent() ? Optional.of(mediaSet) : Optional.empty();
    }
}
