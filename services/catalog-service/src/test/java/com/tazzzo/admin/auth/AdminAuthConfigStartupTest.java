package com.tazzzo.admin.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The all-or-nothing human admin configuration invariant, proven against real Spring binding and startup: absent means
 * DISABLED (service tokens only); complete means enabled; anything partial or contradictory fails startup without
 * printing configured subjects or emails.
 */
class AdminAuthConfigStartupTest {

    @Configuration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ClockConfig.class, AdminAuthConfig.class);

    static final String SUBJECT = "110000000000000000777";
    static final String EMAIL = "label.only@tazzzo.test";

    static List<String> oidc() {
        return new ArrayList<>(List.of(
                "tazzzo.admin.oidc.issuer=https://accounts.google.com",
                "tazzzo.admin.oidc.audience=admin-client.apps.googleusercontent.com",
                "tazzzo.admin.oidc.hosted-domain=tazzzo.test",
                "tazzzo.admin.oidc.credential-label=cms"));
    }

    static List<String> user(int i, String provider, String subject, String email, String roles) {
        String k = "tazzzo.admin.users[" + i + "].";
        List<String> p = new ArrayList<>();
        if (provider != null) p.add(k + "provider=" + provider);
        if (subject != null) p.add(k + "subject=" + subject);
        if (email != null) p.add(k + "email=" + email);
        if (roles != null) p.add(k + "roles=" + roles);
        return p;
    }

    @SafeVarargs
    static String[] props(List<String>... groups) {
        return Arrays.stream(groups).flatMap(List::stream).toArray(String[]::new);
    }

    static List<String> without(List<String> props, String prefix) {
        return props.stream().filter(p -> !p.startsWith(prefix)).toList();
    }

    private void assertFailsMentioning(AssertableApplicationContext ctx, String fragment) {
        assertThat(ctx).hasFailed();
        Throwable root = ctx.getStartupFailure();
        while (root.getCause() != null) root = root.getCause();
        assertThat(root).isInstanceOf(IllegalStateException.class);
        assertThat(root.getMessage()).contains(fragment).doesNotContain(SUBJECT).doesNotContain(EMAIL);
    }

    // ---------- startup works ----------

    @Test
    void fully_absent_oidc_configuration_starts_with_human_oidc_disabled() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(GoogleOidcAuthenticator.class).enabled()).isFalse();
        });
    }

    @Test
    void blank_values_from_empty_environment_placeholders_count_as_absent() {
        runner.withPropertyValues("tazzzo.admin.oidc.issuer=", "tazzzo.admin.oidc.audience=",
                "tazzzo.admin.oidc.hosted-domain=", "tazzzo.admin.oidc.credential-label=").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(GoogleOidcAuthenticator.class).enabled()).isFalse();
        });
    }

    @Test
    void a_complete_configuration_starts_with_human_oidc_enabled_and_no_network_call() {
        runner.withPropertyValues(props(oidc(), user(0, "google", SUBJECT, EMAIL, "reader,cms-writer"),
                user(1, "google", "110000000000000000778", "b@tazzzo.test", "reader"))).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(GoogleOidcAuthenticator.class).enabled()).isTrue();
        });
    }

    @Test
    void oidc_enabled_with_no_users_starts_and_admits_no_human() {
        runner.withPropertyValues(props(oidc())).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(GoogleOidcAuthenticator.class).enabled()).isTrue();
        });
    }

    // ---------- partial OIDC ----------

    @Test
    void issuer_without_audience_fails() {
        runner.withPropertyValues(props(without(oidc(), "tazzzo.admin.oidc.audience")))
                .run(ctx -> assertFailsMentioning(ctx, "tazzzo.admin.oidc.audience is required"));
        runner.withPropertyValues("tazzzo.admin.oidc.issuer=https://accounts.google.com")
                .run(ctx -> assertFailsMentioning(ctx, "tazzzo.admin.oidc.audience is required"));
    }

    @Test
    void audience_without_hosted_domain_fails() {
        runner.withPropertyValues(props(without(oidc(), "tazzzo.admin.oidc.hosted-domain")))
                .run(ctx -> assertFailsMentioning(ctx, "tazzzo.admin.oidc.hosted-domain is required"));
    }

    @Test
    void a_missing_credential_label_or_issuer_fails() {
        runner.withPropertyValues(props(without(oidc(), "tazzzo.admin.oidc.credential-label")))
                .run(ctx -> assertFailsMentioning(ctx, "tazzzo.admin.oidc.credential-label is required"));
        runner.withPropertyValues(props(without(oidc(), "tazzzo.admin.oidc.issuer")))
                .run(ctx -> assertFailsMentioning(ctx, "tazzzo.admin.oidc.issuer is required"));
    }

    @Test
    void a_jwks_uri_alone_is_partial_configuration() {
        runner.withPropertyValues("tazzzo.admin.oidc.jwks-uri=https://keys.example/certs")
                .run(ctx -> assertFailsMentioning(ctx, "is required"));
    }

    @Test
    void a_non_google_issuer_fails() {
        List<String> p = new ArrayList<>(without(oidc(), "tazzzo.admin.oidc.issuer"));
        p.add("tazzzo.admin.oidc.issuer=https://evil.example");
        runner.withPropertyValues(props(p)).run(ctx -> assertFailsMentioning(ctx, "issuer must be"));
    }

    @Test
    void a_malformed_hosted_domain_label_or_remote_http_jwks_uri_fails() {
        List<String> p = new ArrayList<>(without(oidc(), "tazzzo.admin.oidc.hosted-domain"));
        p.add("tazzzo.admin.oidc.hosted-domain=Tazzzo.Test");
        runner.withPropertyValues(props(p)).run(ctx -> assertFailsMentioning(ctx, "hosted-domain must be"));

        List<String> q = new ArrayList<>(without(oidc(), "tazzzo.admin.oidc.credential-label"));
        q.add("tazzzo.admin.oidc.credential-label=Has Spaces");
        runner.withPropertyValues(props(q)).run(ctx -> assertFailsMentioning(ctx, "credential-label must match"));

        List<String> r = new ArrayList<>(oidc());
        r.add("tazzzo.admin.oidc.jwks-uri=http://keys.example/certs");
        runner.withPropertyValues(props(r)).run(ctx -> assertFailsMentioning(ctx, "jwks-uri must be an https URI"));
    }

    @Test
    void the_jwks_uri_override_is_refused_outside_unset_local_test_or_dev() {
        for (String env : new String[]{"staging", "production", "prod", "qa"}) {
            List<String> p = new ArrayList<>(oidc());
            p.add("tazzzo.admin.oidc.jwks-uri=https://keys.example/certs");
            p.add("tazzzo.migration.environment=" + env);
            runner.withPropertyValues(props(p)).run(ctx -> assertFailsMentioning(ctx, "jwks-uri override is refused"));
        }
        for (String env : new String[]{"", "local", "test", "dev"}) {
            List<String> p = new ArrayList<>(oidc());
            p.add("tazzzo.admin.oidc.jwks-uri=https://keys.example/certs");
            p.add("tazzzo.migration.environment=" + env);
            runner.withPropertyValues(props(p)).run(ctx -> assertThat(ctx).hasNotFailed());
        }
        List<String> noOverride = new ArrayList<>(oidc());
        noOverride.add("tazzzo.migration.environment=production");
        runner.withPropertyValues(props(noOverride)).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    // ---------- users ----------

    @Test
    void users_with_oidc_disabled_is_an_impossible_configuration_and_fails() {
        runner.withPropertyValues(props(user(0, "google", SUBJECT, EMAIL, "reader")))
                .run(ctx -> assertFailsMentioning(ctx, "human admins require OIDC"));
    }

    @Test
    void duplicate_provider_and_subject_fails() {
        runner.withPropertyValues(props(oidc(), user(0, "google", SUBJECT, EMAIL, "reader"),
                        user(1, "google", SUBJECT, "other@tazzzo.test", "cms-writer")))
                .run(ctx -> assertFailsMentioning(ctx, "tazzzo.admin.users[1] duplicates an earlier provider+subject"));
    }

    @Test
    void an_unknown_role_fails() {
        for (String role : new String[]{"super_admin", "pricing_write", "admin", "CMS-WRITER", "AUDIT-READER",
                "audit_reader", "auditor", "audit-read", "audit"}) {
            runner.withPropertyValues(props(oidc(), user(0, "google", SUBJECT, EMAIL, "reader," + role)))
                    .run(ctx -> assertFailsMentioning(ctx, "unknown role"));
        }
    }

    @Test
    void audit_reader_is_a_known_role_alone_or_combined() {
        for (String roles : new String[]{"audit-reader", "reader,audit-reader", "cms-writer,audit-reader"}) {
            runner.withPropertyValues(props(oidc(), user(0, "google", SUBJECT, EMAIL, roles)))
                    .run(ctx -> assertThat(ctx).as(roles).hasNotFailed());
        }
    }

    @Test
    void an_empty_role_set_fails() {
        runner.withPropertyValues(props(oidc(), user(0, "google", SUBJECT, EMAIL, null)))
                .run(ctx -> assertFailsMentioning(ctx, "roles must name at least one role"));
    }

    @Test
    void a_blank_subject_fails() {
        runner.withPropertyValues(props(oidc(), user(0, "google", " ", EMAIL, "reader")))
                .run(ctx -> assertFailsMentioning(ctx, "subject must be a non-blank provider subject"));
        runner.withPropertyValues(props(oidc(), user(0, "google", null, EMAIL, "reader")))
                .run(ctx -> assertFailsMentioning(ctx, "subject must be a non-blank provider subject"));
    }

    @Test
    void an_unknown_or_missing_provider_fails() {
        runner.withPropertyValues(props(oidc(), user(0, "github", SUBJECT, EMAIL, "reader")))
                .run(ctx -> assertFailsMentioning(ctx, "provider must be google"));
        runner.withPropertyValues(props(oidc(), user(0, null, SUBJECT, EMAIL, "reader")))
                .run(ctx -> assertFailsMentioning(ctx, "provider must be google"));
    }

    @Test
    void a_blank_or_malformed_email_label_fails() {
        runner.withPropertyValues(props(oidc(), user(0, "google", SUBJECT, null, "reader")))
                .run(ctx -> assertFailsMentioning(ctx, "email must be"));
        runner.withPropertyValues(props(oidc(), user(0, "google", SUBJECT, "not-an-email", "reader")))
                .run(ctx -> assertFailsMentioning(ctx, "email must be"));
    }

    @Test
    void enabled_defaults_to_true_and_false_is_honoured() {
        AdminAuthProperties p = GoogleOidcAuthenticatorTest.properties();
        HumanAdminAllowlist allowlist = HumanAdminSettings.from(p).allowlist();
        assertThat(allowlist.resolve("google", GoogleOidcAuthenticatorTest.WRITER))
                .isInstanceOf(HumanAdminAllowlist.Resolution.Granted.class);
        assertThat(allowlist.resolve("google", GoogleOidcAuthenticatorTest.DISABLED))
                .isEqualTo(new HumanAdminAllowlist.Resolution.Refused(AdminAuthRejection.DISABLED));
        assertThat(new AdminAuthProperties.User().isEnabled()).isTrue();
    }
}
