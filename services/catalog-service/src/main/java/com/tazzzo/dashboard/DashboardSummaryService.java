package com.tazzzo.dashboard;

import com.mongodb.MongoException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The launch operations dashboard: a handful of numbers an operator needs at a glance, each one BOUNDED. Every count
 * either rides an index prefix (orders by status, support cases by status) or carries a hard cap ({@link #CAP}) and a
 * server-side time limit ({@link #MAX_TIME}); a count that hits its cap is reported as {@code capped: true} with the cap
 * as a floor, never as an exact number. No collection metadata or schema introspection is used (the runtime identity has
 * no such authority). Recent audit activity
 * is NOT duplicated here: the admin audit-read API ({@code GET /api/v1/admin/audit-events}) already serves it, paged.
 */
@Service
public class DashboardSummaryService {

    static final long CAP = 10_000;
    static final Duration MAX_TIME = Duration.ofSeconds(2);
    static final Duration RECENT = Duration.ofHours(24);

    public record Count(long value, boolean capped) { }

    private final MongoDatabase db;
    private final Clock clock;
    private final long cap;

    @org.springframework.beans.factory.annotation.Autowired
    public DashboardSummaryService(MongoDatabase db, Clock clock) {
        this(db, clock, CAP);
    }

    DashboardSummaryService(MongoDatabase db, Clock clock, long cap) {
        this.db = db;
        this.clock = clock;
        this.cap = cap;
    }

    Count count(String collection, Bson filter) {
        long n = db.getCollection(collection).countDocuments(filter,
                new CountOptions().limit((int) cap).maxTime(MAX_TIME.toMillis(), TimeUnit.MILLISECONDS));
        return new Count(n, n >= cap);
    }

    public Map<String, Object> summary() {
        Instant since = clock.instant().minus(RECENT);
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Map<String, Object> orders = new LinkedHashMap<>();
            for (String s : List.of("CONFIRMED", "OUT_FOR_DELIVERY")) {
                orders.put("open_" + s.toLowerCase(), count("orders", Filters.eq("status", s)));     // index: order_by_status_recent
            }
            for (String s : List.of("CONFIRMED", "OUT_FOR_DELIVERY", "DELIVERED", "CANCELLED")) {
                orders.put("last24h_" + s.toLowerCase(), count("orders",
                        Filters.and(Filters.eq("status", s), Filters.gte("createdAt", Date.from(since)))));
            }
            out.put("orders", orders);

            Map<String, Object> inventory = new LinkedHashMap<>();
            inventory.put("out_of_stock", count("inventory", Filters.and(Filters.eq("active", true), Filters.lte("on_hand", 0))));
            inventory.put("low_stock", count("inventory", Filters.and(Filters.eq("active", true), Filters.gt("on_hand", 0),
                    Filters.expr(new Document("$lte", List.of("$on_hand", "$low_stock_threshold"))))));
            out.put("inventory", inventory);

            Map<String, Object> catalog = new LinkedHashMap<>();
            catalog.put("products_total", count("products", new Document()));
            catalog.put("active", count("products", Filters.eq("lifecycle", "active")));
            catalog.put("draft", count("products", Filters.eq("lifecycle", "draft")));
            out.put("catalog", catalog);

            Map<String, Object> serviceability = new LinkedHashMap<>();
            serviceability.put("service_areas_total", count("service_areas", new Document()));
            serviceability.put("active", count("service_areas", Filters.eq("active", true)));
            out.put("serviceability", serviceability);

            Map<String, Object> support = new LinkedHashMap<>();
            for (String s : List.of("OPEN", "IN_PROGRESS")) {
                support.put(s.toLowerCase(), count("support_cases", Filters.eq("status", s)));      // index: support_by_status_recent
            }
            out.put("support", support);

            Map<String, Object> notifications = new LinkedHashMap<>();
            for (String s : List.of("PENDING", "FAILED")) {
                notifications.put(s.toLowerCase(), count("notification_outbox", Filters.eq("status", s)));   // index: notification_due
            }
            out.put("notifications", notifications);
        } catch (MongoException e) {
            throw new DashboardUnavailableException();
        }
        out.put("generatedAt", clock.instant().toString());
        out.put("bounds", Map.of("cap", cap, "maxTimeMs", MAX_TIME.toMillis(), "recentWindowHours", RECENT.toHours()));
        return out;
    }

    /** Datastore unavailable or a count exceeded its time limit: 503, never a partial or invented number. */
    static final class DashboardUnavailableException extends RuntimeException {
        DashboardUnavailableException() {
            super("dashboard unavailable");
        }
    }
}
