package com.tazzzo.media;

/** A media command or asset violated a domain invariant. */
public class InvalidMediaException extends MediaException {
    public InvalidMediaException(String message) { super(message); }
}
