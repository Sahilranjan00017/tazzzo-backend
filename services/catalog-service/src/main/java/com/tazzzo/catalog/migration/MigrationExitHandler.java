package com.tazzzo.catalog.migration;

/** Terminates the process with a job's exit code. A bean so tests can observe the code instead of exiting the JVM. */
@FunctionalInterface
public interface MigrationExitHandler {
    void exit(int code);
}
