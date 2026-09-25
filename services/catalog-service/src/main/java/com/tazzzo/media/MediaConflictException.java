package com.tazzzo.media;

/** Optimistic-concurrency conflict: stale expected version, or duplicate canonical create. */
public class MediaConflictException extends MediaException {
    public MediaConflictException(String message) { super(message); }
}
