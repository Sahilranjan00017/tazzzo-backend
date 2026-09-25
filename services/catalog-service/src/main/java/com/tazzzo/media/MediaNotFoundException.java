package com.tazzzo.media;

/** An update addressed a media set that does not exist. */
public class MediaNotFoundException extends MediaException {
    public MediaNotFoundException(String message) { super(message); }
}
