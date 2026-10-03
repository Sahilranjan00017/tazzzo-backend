package com.tazzzo.admin.auth;

import com.tazzzo.common.audit.Actor;
import com.tazzzo.common.audit.ActorType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The admin principal and its typed resolver (no Spring context, no Mongo). */
class AdminPrincipalTest {

    @Test
    void the_shared_tokens_are_service_accounts_never_humans() {
        AdminPrincipal cms = AdminPrincipal.sharedToken(AdminPrincipal.CMS_WRITER);
        AdminPrincipal reader = AdminPrincipal.sharedToken(AdminPrincipal.READER);

        assertThat(cms).isEqualTo(new AdminPrincipal(ActorType.SERVICE_ACCOUNT, "service:cms-writer",
                "shared-token:cms-writer", Set.of("cms-writer")));
        assertThat(reader).isEqualTo(new AdminPrincipal(ActorType.SERVICE_ACCOUNT, "service:reader",
                "shared-token:reader", Set.of("reader")));
        assertThat(cms.canWrite()).isTrue();
        assertThat(reader.canWrite()).isFalse();
        assertThat(reader.hasRole("reader")).isTrue();
    }

    @Test
    void the_actor_of_a_request_is_the_principal_plus_the_request_id() {
        Actor actor = AdminPrincipal.sharedToken(AdminPrincipal.CMS_WRITER).toActor("req_abc");

        assertThat(actor).isEqualTo(new Actor(ActorType.SERVICE_ACCOUNT, "service:cms-writer",
                "shared-token:cms-writer", "req_abc"));
        assertThatThrownBy(() -> AdminPrincipal.sharedToken(AdminPrincipal.CMS_WRITER).toActor(null))
                .as("a request-borne actor needs its request id").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void audit_read_is_only_for_a_human_holding_audit_reader_and_grants_nothing_else() {
        AdminPrincipal auditor = new AdminPrincipal(ActorType.HUMAN_ADMIN, "google:1", "oidc:google:cms",
                Set.of(AdminPrincipal.AUDIT_READER));
        assertThat(auditor.canReadAudit()).isTrue();
        assertThat(auditor.canReadCatalog()).as("audit-reader is not a catalogue reader").isFalse();
        assertThat(auditor.canWrite()).isFalse();

        for (String role : new String[]{AdminPrincipal.READER, AdminPrincipal.CMS_WRITER}) {
            AdminPrincipal human = new AdminPrincipal(ActorType.HUMAN_ADMIN, "google:2", "oidc:google:cms", Set.of(role));
            assertThat(human.canReadAudit()).as(role).isFalse();
            assertThat(human.canReadCatalog()).as(role).isTrue();
            assertThat(AdminPrincipal.sharedToken(role).canReadAudit()).as("shared " + role).isFalse();
        }
        for (ActorType type : new ActorType[]{ActorType.SERVICE_ACCOUNT, ActorType.SYSTEM}) {
            assertThat(new AdminPrincipal(type, "service:x", null, Set.of(AdminPrincipal.AUDIT_READER)).canReadAudit())
                    .as("never a non-human, even if it somehow held the role: " + type).isFalse();
        }
    }

    @Test
    void a_principal_never_holds_an_empty_identity_or_role_set() {
        assertThatThrownBy(() -> new AdminPrincipal(null, "service:x", null, Set.of("reader")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdminPrincipal(ActorType.SERVICE_ACCOUNT, " ", null, Set.of("reader")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdminPrincipal(ActorType.SERVICE_ACCOUNT, "service:x", "", Set.of("reader")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdminPrincipal(ActorType.SERVICE_ACCOUNT, "service:x", null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_resolver_returns_the_typed_principal_and_never_invents_one() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThat(AdminPrincipalResolver.current(request)).as("no anonymous admin").isEmpty();
        assertThatThrownBy(() -> AdminPrincipalResolver.require(request)).isInstanceOf(IllegalStateException.class);

        AdminPrincipal cms = AdminPrincipal.sharedToken(AdminPrincipal.CMS_WRITER);
        AdminPrincipalResolver.attach(request, cms);
        assertThat(AdminPrincipalResolver.require(request)).isSameAs(cms);

        request.setAttribute(AdminPrincipalResolver.ATTRIBUTE, "cms-writer");
        assertThatThrownBy(() -> AdminPrincipalResolver.current(request)).as("a wrong-typed attribute fails loud")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AdminPrincipalResolver.attach(request, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
