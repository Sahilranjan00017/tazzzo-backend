package com.tazzzo.dashboard;

import com.mongodb.client.MongoDatabase;

import java.time.Clock;
import java.util.Map;

/** Test seam: a summary with a small cap, to prove the capped path without seeding ten thousand rows. */
public final class DashboardTestAccess {
    private DashboardTestAccess() { }

    public static Map<String, Object> summaryWithCap(MongoDatabase db, long cap) {
        return new DashboardSummaryService(db, Clock.systemUTC(), cap).summary();
    }
}
