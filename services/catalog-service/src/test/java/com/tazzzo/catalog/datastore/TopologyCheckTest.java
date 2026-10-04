package com.tazzzo.catalog.datastore;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TopologyCheckTest {

    private static Document hello(Object setName, Object msg, Object sessions, Object wire) {
        Document d = new Document();
        if (setName != null) d.append("setName", setName);
        if (msg != null) d.append("msg", msg);
        if (sessions != null) d.append("logicalSessionTimeoutMinutes", sessions);
        if (wire != null) d.append("maxWireVersion", wire);
        return d;
    }

    @Test
    void a_replica_set_with_sessions_on_mongodb_7_or_newer_is_accepted() {
        assertThat(TopologyCheck.evaluate(hello("rs0", null, 30, 21))).isEmpty();
        assertThat(TopologyCheck.evaluate(hello("rs0", null, 30, 25))).isEmpty();
        assertThat(TopologyCheck.evaluate(hello(null, "isdbgrid", 30, 21))).as("a mongos router supports transactions").isEmpty();
    }

    @Test
    void a_standalone_server_is_refused_because_every_write_uses_a_transaction() {
        assertThat(TopologyCheck.evaluate(hello(null, null, 30, 21))).extracting(Violation::code)
                .containsExactly("TRANSACTIONS_UNSUPPORTED_TOPOLOGY");
    }

    @Test
    void no_session_support_and_old_servers_are_refused() {
        assertThat(TopologyCheck.evaluate(hello("rs0", null, null, 21))).extracting(Violation::code).containsExactly("SESSIONS_UNSUPPORTED");
        assertThat(TopologyCheck.evaluate(hello("rs0", null, 30, 20))).extracting(Violation::code).containsExactly("SERVER_VERSION_TOO_OLD");
        assertThat(TopologyCheck.evaluate(hello("rs0", null, 30, null))).extracting(Violation::code).containsExactly("SERVER_VERSION_TOO_OLD");
        assertThat(TopologyCheck.evaluate(new Document())).extracting(Violation::code)
                .containsExactlyInAnyOrder("TRANSACTIONS_UNSUPPORTED_TOPOLOGY", "SESSIONS_UNSUPPORTED", "SERVER_VERSION_TOO_OLD");
    }
}
