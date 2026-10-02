package com.tazzzo.common.audit;

/** The actor that test code passes to audited service methods it calls directly (never an HTTP admin identity). */
public final class TestActors {

    public static final Actor TEST = Actor.system("system:test");

    private TestActors() {
    }
}
