package com.tazzzo.admin.audit;

import com.tazzzo.common.audit.ActorType;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The audit-read query grammar and cursor codec, without Spring or Mongo. */
class AuditEventQueryTest {

    static AuditEventQuery parse(String... kv) {
        Map<String, String[]> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], new String[]{kv[i + 1]});
        }
        return AuditEventQuery.parse(m);
    }

    static void rejected(String message, String... kv) {
        assertThatThrownBy(() -> parse(kv)).as(String.join("=", kv)).isInstanceOf(AuditQueryRejected.class)
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }

    @Test
    void no_parameters_is_the_default_newest_first_page_of_50() {
        AuditEventQuery q = parse();
        assertThat(q.limit()).isEqualTo(50);
        assertThat(q.actorType()).isEmpty();
        assertThat(q.cursor()).isEmpty();
    }

    @Test
    void every_supported_filter_parses() {
        AuditEventQuery q = parse("actorType", "HUMAN_ADMIN", "actorId", "google:1100", "action", "PRICE_UPDATED",
                "targetType", "product", "targetId", "TZP-1", "requestId", "req_0123456789abcdef0123",
                "from", "2026-10-01T00:00:00Z", "to", "2026-10-02T00:00:00.5Z", "limit", "7");
        assertThat(q.actorType()).contains(ActorType.HUMAN_ADMIN);
        assertThat(q.actorId()).contains("google:1100");
        assertThat(q.from()).contains(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(q.to()).contains(Instant.parse("2026-10-02T00:00:00.500Z"));
        assertThat(q.limit()).isEqualTo(7);
        for (String id : new String[]{"service:cms-writer", "system:taint-worker", "google:abc_DEF-1"}) {
            assertThat(parse("actorId", id).actorId()).contains(id);
        }
    }

    @Test
    void limit_is_1_to_100() {
        assertThat(parse("limit", "1").limit()).isEqualTo(1);
        assertThat(parse("limit", "100").limit()).isEqualTo(100);
        for (String bad : new String[]{"0", "101", "999", "-1", "+5", "1.0", "abc", "1000"}) {
            rejected("limit must be between 1 and 100", "limit", bad);
        }
    }

    @Test
    void only_allowlisted_names_once_each_and_never_empty() {
        for (String name : new String[]{"sort", "filter", "$where", "actorId[$ne]", "actor.id", "detail", "offset"}) {
            rejected("unsupported query parameter", name, "x");
        }
        Map<String, String[]> repeated = new HashMap<>();
        repeated.put("action", new String[]{"A", "B"});
        assertThatThrownBy(() -> AuditEventQuery.parse(repeated)).hasMessage("query parameter action must appear exactly once");
        rejected("query parameter action must not be empty", "action", "");
    }

    @Test
    void values_outside_their_closed_format_are_rejected_without_echo() {
        rejected("actorId has an invalid format", "actorId", "{\"$ne\":null}");
        rejected("actorId has an invalid format", "actorId", "admin:root");
        rejected("action has an invalid format", "action", ".*");
        rejected("requestId has an invalid format", "requestId", "req_0123456789ABCDEF0123");
        rejected("targetType has an invalid format", "targetType", "Product");
        rejected("targetId has an invalid format", "targetType", "product", "targetId", "-x");
        rejected("actorType must be HUMAN_ADMIN, SERVICE_ACCOUNT or SYSTEM", "actorType", "ADMIN");
        rejected("from must be a UTC instant, e.g. 2026-10-03T10:00:00Z", "from", "2026-10-01T00:00:00+01:00");
        rejected("to must be a UTC instant, e.g. 2026-10-03T10:00:00Z", "to", "2026-02-30T00:00:00Z");
    }

    @Test
    void ranges_must_be_ordered_and_target_id_needs_a_type() {
        assertThat(parse("from", "2026-10-01T00:00:00Z", "to", "2026-10-01T00:00:00Z").from()).isPresent();
        rejected("from must not be after to", "from", "2026-10-01T00:00:01Z", "to", "2026-10-01T00:00:00Z");
        rejected("targetId requires targetType", "targetId", "TZP-1");
    }

    @Test
    void the_cursor_round_trips_and_is_bound_to_its_filters() {
        AuditEventQuery q = parse("action", "CREATED");
        ObjectId id = new ObjectId();
        AuditCursor c = new AuditCursor(1_759_000_000_123L, AuditSource.TAXONOMY_NODE, id, AuditCursor.fingerprint(q));
        String token = c.encode();
        assertThat(token).matches("^[A-Za-z0-9_-]+$");

        AuditCursor back = AuditCursor.decode(token, parse("action", "CREATED", "limit", "5"));
        assertThat(back).isEqualTo(c);
        assertThatThrownBy(() -> AuditCursor.decode(token, parse("action", "DELETED")))
                .hasMessage("cursor does not belong to these filters");
        assertThatThrownBy(() -> AuditCursor.decode(token, parse())).hasMessage("cursor does not belong to these filters");
    }

    @Test
    void a_malformed_cursor_is_rejected() {
        AuditEventQuery q = parse();
        String fp = AuditCursor.fingerprint(q);
        String hex = new ObjectId().toHexString();
        for (String plain : new String[]{"", "v1", "v2|1|pe|" + hex + "|" + fp, "v1|01|pe|" + hex + "|" + fp,
                "v1|-1|pe|" + hex + "|" + fp, "v1|1|xx|" + hex + "|" + fp, "v1|1|pe|" + hex.toUpperCase() + "|" + fp,
                "v1|1|pe|" + hex + "|" + fp + "|extra", "v1|1|pe|" + hex + "|" + fp + "\n",
                "v1|1234567890123456|pe|" + hex + "|" + fp}) {
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.US_ASCII));
            assertThatThrownBy(() -> AuditCursor.decode(token, q)).as(plain).isInstanceOf(AuditQueryRejected.class);
        }
        assertThatThrownBy(() -> AuditCursor.decode("*", q)).hasMessage("cursor is malformed");
        assertThat(AuditCursor.decode(new AuditCursor(1, AuditSource.DOMAIN, new ObjectId(hex), fp).encode(), q).source())
                .isEqualTo(AuditSource.DOMAIN);
    }

    @Test
    void target_types_route_to_their_ledgers_only() {
        assertThat(AuditSource.PRODUCT.admitsTargetType("product")).isTrue();
        assertThat(AuditSource.TAXONOMY_NODE.admitsTargetType("product")).isFalse();
        assertThat(AuditSource.DOMAIN.admitsTargetType("product")).isFalse();
        assertThat(AuditSource.DOMAIN.admitsTargetType("taxonomy_node")).isFalse();
        assertThat(AuditSource.DOMAIN.admitsTargetType("serviceability_pin")).isTrue();
        assertThat(AuditSource.PRODUCT.admitsTargetType("serviceability_pin")).isFalse();
    }
}
