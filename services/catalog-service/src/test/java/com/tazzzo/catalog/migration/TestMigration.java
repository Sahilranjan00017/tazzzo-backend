package com.tazzzo.catalog.migration;

import com.mongodb.client.MongoDatabase;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** A configurable synthetic migration for exercising the runner's behaviour. */
final class TestMigration implements Migration {

    final String id;
    final MigrationKind kind;
    volatile String definition = "v1";
    volatile boolean enabledByDefault = true;
    volatile Preflight preflight = Preflight.ready(List.of("synthetic operation"), List.of());
    volatile Runnable action = () -> { };
    volatile Supplier<List<String>> validation = List::of;
    final AtomicInteger applies = new AtomicInteger();

    TestMigration(String id) {
        this(id, MigrationKind.SCHEMA);
    }

    TestMigration(String id, MigrationKind kind) {
        this.id = id;
        this.kind = kind;
    }

    @Override public String id() { return id; }
    @Override public String description() { return "synthetic " + id; }
    @Override public MigrationKind kind() { return kind; }
    @Override public List<String> collections() { return List.of("synthetic"); }
    @Override public String definition() { return definition; }
    @Override public boolean enabledByDefault() { return enabledByDefault; }
    volatile RuntimeException preflightFailure;

    @Override
    public Preflight preflight(MongoDatabase db) {
        if (preflightFailure != null) throw preflightFailure;
        return preflight;
    }

    @Override
    public ApplyResult apply(MongoDatabase db) {
        applies.incrementAndGet();
        action.run();
        return ApplyResult.of("applied " + id);
    }

    @Override
    public List<String> validate(MongoDatabase db) {
        return validation.get();
    }
}
