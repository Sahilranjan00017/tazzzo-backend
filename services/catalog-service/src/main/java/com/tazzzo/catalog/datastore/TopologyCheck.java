package com.tazzzo.catalog.datastore;

import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks the server's {@code hello} reply against what the backend needs (DB-4): every write is a multi-document
 * transaction, so the deployment must be a replica set (or a mongos router) with session support, on a server at
 * least as new as the version this code is tested against (MongoDB 7.0, wire version {@value #MIN_WIRE_VERSION}).
 */
public final class TopologyCheck {

    /** MongoDB 7.0. */
    public static final int MIN_WIRE_VERSION = 21;

    private TopologyCheck() {
    }

    public static List<Violation> evaluate(Document hello) {
        List<Violation> v = new ArrayList<>();
        boolean replicaSet = hello.getString("setName") != null;
        boolean mongos = "isdbgrid".equals(hello.getString("msg"));
        if (!replicaSet && !mongos) {
            v.add(new Violation("TRANSACTIONS_UNSUPPORTED_TOPOLOGY",
                    "the server is a standalone deployment; multi-document transactions need a replica set"));
        }
        if (hello.get("logicalSessionTimeoutMinutes") == null) {
            v.add(new Violation("SESSIONS_UNSUPPORTED", "the server reports no session support; transactions need sessions"));
        }
        Number wire = hello.get("maxWireVersion", Number.class);
        if (wire == null || wire.intValue() < MIN_WIRE_VERSION) {
            v.add(new Violation("SERVER_VERSION_TOO_OLD", "the server is older than MongoDB 7.0 (wire version "
                    + MIN_WIRE_VERSION + "), which this code is tested against"));
        }
        return v;
    }
}
