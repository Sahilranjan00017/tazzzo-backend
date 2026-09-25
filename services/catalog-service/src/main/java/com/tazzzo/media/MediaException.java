package com.tazzzo.media;

/** Base for typed media failures. Messages are internal; never surfaced to public clients. */
public abstract class MediaException extends RuntimeException {
    protected MediaException(String message) { super(message); }
}
