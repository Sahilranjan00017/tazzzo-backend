package com.tazzzo.commerce.read;

/**
 * A location shape this layer deliberately does not resolve yet (PR-08, STEP 5): lat/lng is a
 * RESERVED contract field with no resolution implementation — treating it silently as anonymous
 * would misreport coverage, so it fails typed for the future API layer to map.
 */
public class UnsupportedLocationException extends RuntimeException {
    public UnsupportedLocationException(String message) { super(message); }
}
