package com.tazzzo.catalog.api;

import com.tazzzo.admin.audit.AuditEventQuery;
import com.tazzzo.admin.audit.AuditEventReader;
import com.tazzzo.catalog.api.ApiDtos.AuditEventDto;
import com.tazzzo.catalog.api.ApiDtos.AuditEventsResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Structural pins of the audit-read contract that behaviour alone cannot distinguish (no Spring, no Mongo). */
class AdminAuditEventsContractTest {

    @Test
    void the_controller_returns_the_explicit_dto_never_an_entity_or_internal_page() {
        Method[] handlers = Arrays.stream(AdminAuditEventsController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(GetMapping.class) || m.isAnnotationPresent(RequestMapping.class))
                .toArray(Method[]::new);
        assertThat(handlers).hasSize(1);
        assertThat(handlers[0].getReturnType()).isEqualTo(AuditEventsResponse.class);
        assertThat(handlers[0].isAnnotationPresent(GetMapping.class)).as("GET only, no RequestMapping with methods").isTrue();
        assertThat(handlers[0].getAnnotation(GetMapping.class).value()).containsExactly("/audit-events");
    }

    @Test
    void the_dto_is_exactly_the_allowlisted_fields_of_plain_types() {
        assertThat(Arrays.stream(AuditEventDto.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("id", "occurredAt", "action", "targetType", "targetId", "actorType", "actorId",
                        "credentialId", "requestId");
        assertThat(Arrays.stream(AuditEventDto.class.getRecordComponents()).map(RecordComponent::getType))
                .containsOnly(String.class);
        assertThat(Arrays.stream(AuditEventsResponse.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("items", "nextCursor");
    }

    @Test
    void every_ledger_query_sorts_by_time_then_id_descending_and_reads_only_attributed_rows() {
        AuditEventReader reader = new AuditEventReader(null);
        List<AuditEventReader.LedgerQuery> plan = reader.plan(AuditEventQuery.parse(Map.of()));
        assertThat(plan).hasSize(3);
        for (AuditEventReader.LedgerQuery q : plan) {
            assertThat(q.sort().toBsonDocument().toJson()).as(q.source().name()).isEqualTo("{\"at\": -1, \"_id\": -1}");
            assertThat(q.filter().toBsonDocument().toJson()).contains("\"actor\"").contains("object");
            assertThat(q.limit()).isEqualTo(AuditEventQuery.DEFAULT_LIMIT + 1);
            assertThat(q.projection().toBsonDocument().toJson()).doesNotContain("detail");
        }
    }
}
