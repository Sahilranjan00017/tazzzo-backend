package com.tazzzo.admin.auth;

import com.tazzzo.common.audit.ActorType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The email label is display metadata keyed by provider + subject: it never changes identity or authorization. */
class AdminProfilesTest {

    static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");

    static AdminAuthProperties withWriterLabel(String label) {
        AdminAuthProperties p = GoogleIdTokens.properties();
        p.setUsers(List.of(
                GoogleIdTokens.user(GoogleIdTokens.WRITER, label, List.of("cms-writer"), true),
                GoogleIdTokens.user(GoogleIdTokens.READER, "reader@tazzzo.test", List.of("reader"), true)));
        return p;
    }

    static AdminPrincipal human(String sub, String... roles) {
        return new AdminPrincipal(ActorType.HUMAN_ADMIN, "google:" + sub, GoogleIdTokens.CREDENTIAL_ID, Set.of(roles));
    }

    @Test
    void a_google_human_admin_resolves_to_its_configured_label() {
        AdminProfiles profiles = new AdminProfiles(HumanAdminSettings.from(withWriterLabel("writer@tazzzo.test")).allowlist());
        assertThat(profiles.emailLabel(human(GoogleIdTokens.WRITER, "cms-writer"))).contains("writer@tazzzo.test");
        assertThat(profiles.emailLabel(human(GoogleIdTokens.READER, "reader"))).contains("reader@tazzzo.test");
    }

    @Test
    void service_accounts_unknown_subjects_and_non_google_ids_have_no_label() {
        AdminProfiles profiles = new AdminProfiles(HumanAdminSettings.from(withWriterLabel("writer@tazzzo.test")).allowlist());
        assertThat(profiles.emailLabel(AdminPrincipal.sharedToken(AdminPrincipal.CMS_WRITER))).isEmpty();
        assertThat(profiles.emailLabel(AdminPrincipal.sharedToken(AdminPrincipal.READER))).isEmpty();
        assertThat(profiles.emailLabel(human(GoogleIdTokens.STRANGER, "reader"))).isEmpty();
        assertThat(profiles.emailLabel(new AdminPrincipal(ActorType.HUMAN_ADMIN, "github:" + GoogleIdTokens.WRITER, "x",
                Set.of("reader")))).isEmpty();
        assertThat(profiles.emailLabel(new AdminPrincipal(ActorType.SERVICE_ACCOUNT, "google:" + GoogleIdTokens.WRITER, "x",
                Set.of("reader")))).as("only a HUMAN_ADMIN principal has a label").isEmpty();
        assertThat(new AdminProfiles(HumanAdminSettings.from(new AdminAuthProperties()).allowlist())
                .emailLabel(human(GoogleIdTokens.WRITER, "cms-writer"))).as("OIDC disabled").isEmpty();
    }

    @Test
    void changing_the_configured_label_changes_only_the_label_never_identity_or_authorization() {
        GoogleIdTokens tokens = new GoogleIdTokens("kid-profile");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        String token = tokens.token(GoogleIdTokens.WRITER, NOW);
        AdminPrincipal[] principals = new AdminPrincipal[2];
        String[] labels = {"writer@tazzzo.test", "renamed.writer@tazzzo.test"};
        String[] resolved = new String[2];
        for (int i = 0; i < 2; i++) {
            HumanAdminSettings settings = HumanAdminSettings.from(withWriterLabel(labels[i]));
            GoogleOidcAuthenticator authenticator = new GoogleOidcAuthenticator(
                    new GoogleOidcVerifier(GoogleIdTokens.settings(), tokens.trustedKeys(), clock), settings.allowlist(),
                    GoogleIdTokens.CREDENTIAL_ID);
            AdminAuthentication result = authenticator.authenticate(
                    AdminBearerCredential.fromAuthorizationHeader("Bearer " + token).orElseThrow());
            principals[i] = ((AdminAuthentication.Authenticated) result).principal();
            resolved[i] = new AdminProfiles(settings.allowlist()).emailLabel(principals[i]).orElseThrow();
        }
        assertThat(resolved).containsExactly(labels[0], labels[1]);
        assertThat(principals[1]).as("same actor id, credential and roles").isEqualTo(principals[0]);
        assertThat(principals[1].toActor("req_x")).isEqualTo(principals[0].toActor("req_x"));
        assertThat(principals[0].toString()).doesNotContain("@");
    }

    @Test
    void the_label_is_never_derived_from_the_token_email() {
        GoogleIdTokens tokens = new GoogleIdTokens("kid-profile-2");
        HumanAdminSettings settings = HumanAdminSettings.from(withWriterLabel("writer@tazzzo.test"));
        GoogleOidcAuthenticator authenticator = new GoogleOidcAuthenticator(
                new GoogleOidcVerifier(GoogleIdTokens.settings(), tokens.trustedKeys(), Clock.fixed(NOW, ZoneOffset.UTC)),
                settings.allowlist(), GoogleIdTokens.CREDENTIAL_ID);
        String token = tokens.token(GoogleIdTokens.WRITER, NOW, c -> c.put("email", "someone.else@tazzzo.test"));
        AdminPrincipal p = ((AdminAuthentication.Authenticated) authenticator.authenticate(
                AdminBearerCredential.fromAuthorizationHeader("Bearer " + token).orElseThrow())).principal();
        assertThat(new AdminProfiles(settings.allowlist()).emailLabel(p)).contains("writer@tazzzo.test");
    }
}
