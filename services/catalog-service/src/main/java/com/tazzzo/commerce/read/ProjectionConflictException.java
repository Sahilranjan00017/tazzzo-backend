package com.tazzzo.commerce.read;

/** Concurrent rebuilders exhausted bounded retries without converging. */
public class ProjectionConflictException extends RuntimeException {
    public ProjectionConflictException(String message) { super(message); }
}
