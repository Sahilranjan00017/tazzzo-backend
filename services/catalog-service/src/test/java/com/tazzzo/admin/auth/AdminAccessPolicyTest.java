package com.tazzzo.admin.auth;

import com.tazzzo.common.audit.ActorType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tazzzo.admin.auth.AdminAccessPolicy.Decision.ALLOW;
import static com.tazzzo.admin.auth.AdminAccessPolicy.Decision.FORBIDDEN_READ;
import static com.tazzzo.admin.auth.AdminAccessPolicy.Decision.FORBIDDEN_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

/** The access matrix, without Spring: every role against every namespace and method. */
class AdminAccessPolicyTest {

    static final Set<String> NARROW = Set.of("/api/v1/admin/me", "/api/v1/admin/audit-events");

    static AdminPrincipal human(String... roles) {
        return new AdminPrincipal(ActorType.HUMAN_ADMIN, "google:1", "oidc:google:t", Set.of(roles));
    }

    static AdminPrincipal service(String role) {
        return AdminPrincipal.sharedToken(role);
    }

    static AdminAccessPolicy.Decision d(AdminPrincipal p, String method, String uri) {
        return AdminAccessPolicy.decide(p, method, uri, NARROW);
    }

    @Test
    void order_ops_owns_the_orders_namespace_and_reads_support() {
        AdminPrincipal p = human(AdminPrincipal.ORDER_OPS);
        for (String uri : new String[]{"/api/v1/admin/orders", "/api/v1/admin/orders/ORD_1", "/api/v1/admin/orders/ORD_1/transition"}) {
            assertThat(d(p, "GET", uri)).as(uri).isEqualTo(ALLOW);
            assertThat(d(p, "POST", uri)).as(uri).isEqualTo(ALLOW);
        }
        assertThat(d(p, "GET", "/api/v1/admin/support/cases")).isEqualTo(ALLOW);
        assertThat(d(p, "POST", "/api/v1/admin/support/cases")).isEqualTo(FORBIDDEN_WRITE);
    }

    @Test
    void support_agent_owns_support_and_only_reads_orders() {
        AdminPrincipal p = human(AdminPrincipal.SUPPORT_AGENT);
        assertThat(d(p, "GET", "/api/v1/admin/support/cases")).isEqualTo(ALLOW);
        assertThat(d(p, "PUT", "/api/v1/admin/support/cases/1")).isEqualTo(ALLOW);
        assertThat(d(p, "GET", "/api/v1/admin/orders/ORD_1")).isEqualTo(ALLOW);
        assertThat(d(p, "POST", "/api/v1/admin/orders/ORD_1/transition")).isEqualTo(FORBIDDEN_WRITE);
        assertThat(d(p, "DELETE", "/api/v1/admin/orders/ORD_1")).isEqualTo(FORBIDDEN_WRITE);
    }

    @Test
    void no_other_role_and_no_shared_token_reaches_a_staff_namespace() {
        AdminPrincipal[] others = {human(AdminPrincipal.CMS_WRITER), human(AdminPrincipal.READER), human(AdminPrincipal.AUDIT_READER),
                human(AdminPrincipal.CMS_WRITER, AdminPrincipal.READER, AdminPrincipal.AUDIT_READER),
                service(AdminPrincipal.CMS_WRITER), service(AdminPrincipal.READER)};
        for (AdminPrincipal p : others) {
            for (String ns : new String[]{AdminAccessPolicy.ORDERS, AdminAccessPolicy.SUPPORT}) {
                assertThat(d(p, "GET", ns + "/x")).as(p + " " + ns).isEqualTo(FORBIDDEN_READ);
                assertThat(d(p, "POST", ns + "/x")).as(p + " " + ns).isEqualTo(FORBIDDEN_WRITE);
                assertThat(d(p, "GET", ns)).isEqualTo(FORBIDDEN_READ);
            }
        }
    }

    @Test
    void a_staff_role_on_a_service_account_is_worthless() {
        // the shared tokens can never carry one in production; the policy still refuses the combination
        AdminPrincipal forged = new AdminPrincipal(ActorType.SERVICE_ACCOUNT, "service:x", "c", Set.of(AdminPrincipal.ORDER_OPS));
        assertThat(d(forged, "GET", "/api/v1/admin/orders/ORD_1")).isEqualTo(FORBIDDEN_READ);
        assertThat(d(forged, "POST", "/api/v1/admin/orders/ORD_1")).isEqualTo(FORBIDDEN_WRITE);
        assertThat(forged.isStaff()).isFalse();
    }

    @Test
    void staff_roles_confer_nothing_on_the_legacy_surface() {
        for (AdminPrincipal p : new AdminPrincipal[]{human(AdminPrincipal.ORDER_OPS), human(AdminPrincipal.SUPPORT_AGENT),
                human(AdminPrincipal.ORDER_OPS, AdminPrincipal.SUPPORT_AGENT)}) {
            for (String uri : new String[]{"/api/v1/products", "/api/v1/taxonomy/nodes", "/api/v1/admin/service-areas", "/api/v1/admin/prices/TZP-1",
                    "/api/v1/admin/inventory/TZP-1/L", "/api/v1/admin/audit-events", "/api/v1/evidence/E1"}) {
                // the filter hands a staff-only principal ONLY /me as its narrow read path (never the audit read)
                assertThat(AdminAccessPolicy.decide(p, "GET", uri, Set.of("/api/v1/admin/me"))).as(uri).isEqualTo(FORBIDDEN_READ);
                assertThat(d(p, "PUT", uri)).as(uri).isEqualTo(FORBIDDEN_WRITE);
            }
        }
    }

    @Test
    void the_legacy_rules_are_unchanged() {
        assertThat(d(human(AdminPrincipal.CMS_WRITER), "POST", "/api/v1/products")).isEqualTo(ALLOW);
        assertThat(d(human(AdminPrincipal.READER), "GET", "/api/v1/products")).isEqualTo(ALLOW);
        assertThat(d(human(AdminPrincipal.READER), "POST", "/api/v1/products")).isEqualTo(FORBIDDEN_WRITE);
        assertThat(d(service(AdminPrincipal.CMS_WRITER), "PUT", "/api/v1/admin/prices/TZP-1")).isEqualTo(ALLOW);
        assertThat(d(human(AdminPrincipal.AUDIT_READER), "GET", "/api/v1/admin/audit-events")).isEqualTo(ALLOW);
        assertThat(d(human(AdminPrincipal.AUDIT_READER), "GET", "/api/v1/products")).isEqualTo(FORBIDDEN_READ);
        assertThat(d(human(AdminPrincipal.AUDIT_READER), "POST", "/api/v1/admin/audit-events")).isEqualTo(FORBIDDEN_WRITE);
    }

    @Test
    void the_namespace_match_is_exact_on_the_segment_boundary() {
        AdminPrincipal ops = human(AdminPrincipal.ORDER_OPS);
        // lookalikes are NOT the namespace: they fall to the legacy rules, where a staff role confers nothing
        for (String uri : new String[]{"/api/v1/admin/orders2", "/api/v1/admin/orders2/x", "/api/v1/admin/ordersX", "/api/v1/admin/Orders/x",
                "/api/v1/admin/order/x", "/api/v1/admin/orders.json", "/api/v1/admin/support2/x", "/api/v1/admin//orders/x", "/api/v1/admin/x/orders/y"}) {
            assertThat(d(ops, "GET", uri)).as(uri).isEqualTo(FORBIDDEN_READ);
        }
        // and a catalogue writer cannot reach the real namespace by decorating it
        AdminPrincipal writer = human(AdminPrincipal.CMS_WRITER);
        for (String uri : new String[]{"/api/v1/admin/orders", "/api/v1/admin/orders/", "/api/v1/admin/orders/a/b/c"}) {
            assertThat(d(writer, "GET", uri)).as(uri).isEqualTo(FORBIDDEN_READ);
        }
    }

    @Test
    void the_me_endpoint_is_reachable_by_a_staff_only_principal_only_through_its_own_narrow_set() {
        AdminPrincipal ops = human(AdminPrincipal.ORDER_OPS);
        assertThat(AdminAccessPolicy.decide(ops, "GET", "/api/v1/admin/me", Set.of("/api/v1/admin/me"))).isEqualTo(ALLOW);
        assertThat(AdminAccessPolicy.decide(ops, "GET", "/api/v1/admin/audit-events", Set.of("/api/v1/admin/me"))).isEqualTo(FORBIDDEN_READ);
    }

    /** Spring routes /orders;x/... and /%6frders to the staff controllers; the policy must never see past them. */
    @Test
    void path_parameters_and_percent_escapes_are_refused_for_every_principal() {
        for (String uri : new String[]{"/api/v1/admin/orders;x", "/api/v1/admin/orders;/ORD_abc", "/api/v1/admin/support;/cases",
                "/api/v1/admin/%6frders", "/api/v1/admin/support/%63ases", "/api/v1/products;jsessionid=1"}) {
            for (AdminPrincipal p : new AdminPrincipal[]{service("read"), service("cms-writer"), human("order-ops", "support-agent")}) {
                assertThat(d(p, "GET", uri)).as(uri).isEqualTo(FORBIDDEN_READ);
                assertThat(d(p, "POST", uri)).as(uri).isEqualTo(FORBIDDEN_WRITE);
            }
        }
    }
}
