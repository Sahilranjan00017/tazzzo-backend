package com.tazzzo.common.audit;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The neutral audit actor: invariants, the persisted {@code actor} sub-document and STRICT reading (no Mongo). */
class ActorTest {

    private static final Actor SERVICE = new Actor(ActorType.SERVICE_ACCOUNT, "service:cms-writer",
            "shared-token:cms-writer", "req_0123456789abcdefghij");

    @Test
    void valid_actors_of_every_kind() {
        assertThat(SERVICE.type()).isEqualTo(ActorType.SERVICE_ACCOUNT);
        assertThat(new Actor(ActorType.HUMAN_ADMIN, "admin:u-1", null, "req_x").credentialId()).isNull();
        Actor system = Actor.system("system:taint-worker");
        assertThat(system.type()).isEqualTo(ActorType.SYSTEM);
        assertThat(system.requestId()).as("system work has no request").isNull();
    }

    @Test
    void identity_fields_are_never_blank_and_request_borne_actors_need_their_request_id() {
        assertThatThrownBy(() -> new Actor(null, "service:x", null, "req_1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Actor(ActorType.SERVICE_ACCOUNT, " ", null, "req_1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Actor(ActorType.SERVICE_ACCOUNT, "service:x", "", "req_1"))
                .as("credentialId optional but never blank").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Actor(ActorType.SERVICE_ACCOUNT, "service:x", null, null))
                .as("an HTTP actor without its request id").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Actor(ActorType.HUMAN_ADMIN, "admin:u-1", null, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Actor.system("taint-worker")).as("system ids are system:<name>")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Actor.system("system:")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Actor(ActorType.SERVICE_ACCOUNT, "service:\nx", null, "req_1"))
                .as("no control characters").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_persisted_shape_round_trips_and_omits_absent_optional_fields() {
        Document d = ActorDocuments.toDocument(SERVICE);
        assertThat(d.keySet()).containsExactlyInAnyOrder("type", "id", "credential_id", "request_id");
        assertThat(d.getString("type")).isEqualTo("SERVICE_ACCOUNT");
        assertThat(ActorDocuments.fromDocument(d)).isEqualTo(SERVICE);

        Document system = ActorDocuments.toDocument(Actor.system("system:merge-finalizer"));
        assertThat(system.keySet()).containsExactlyInAnyOrder("type", "id");
        assertThat(ActorDocuments.fromDocument(system)).isEqualTo(Actor.system("system:merge-finalizer"));
    }

    @Test
    void a_legacy_event_without_an_actor_is_valid_and_stays_unattributed() {
        Document legacy = new Document("type", "MINTED").append("product_id", "TZP-1").append("detail", new Document());

        assertThat(ActorDocuments.fromEvent(legacy)).as("absent is NOT a fabricated SYSTEM/service/human actor").isEmpty();
        assertThat(ActorDocuments.appendTo(new Document("type", "X"), null).containsKey("actor"))
                .as("an unattributed event never writes an actor placeholder").isFalse();
        assertThat(ActorDocuments.fromEvent(ActorDocuments.appendTo(new Document("type", "X"), SERVICE))).contains(SERVICE);
    }

    private static void malformed(Consumer<Document> mutation) {
        Document actor = ActorDocuments.toDocument(SERVICE);
        mutation.accept(actor);
        assertThatThrownBy(() -> ActorDocuments.fromEvent(new Document("type", "X").append("actor", actor)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_present_but_malformed_actor_fails_loud_never_silently_accepted() {
        malformed(a -> a.remove("type"));                         // type missing
        malformed(a -> a.remove("id"));                           // id missing
        malformed(a -> a.remove("request_id"));                   // request id missing on a request-borne actor
        malformed(a -> a.put("id", ""));                          // blank id
        malformed(a -> a.put("id", 7));                           // wrong BSON type
        malformed(a -> a.put("type", "ROBOT"));                   // unknown type
        malformed(a -> a.put("type", 1));
        malformed(a -> a.put("credential_id", " "));
        malformed(a -> a.put("display_name", "Someone"));         // foreign field
        for (Object bad : new Object[]{null, "service:cms-writer", 1, List.of(), new Document()}) {
            assertThatThrownBy(() -> ActorDocuments.fromEvent(new Document("actor", bad))).as("%s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
