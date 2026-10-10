package com.tazzzo.admin.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires human admin OIDC from {@link AdminAuthProperties}. Validation runs here, so an invalid configuration fails
 * startup. Disabled OIDC yields an authenticator that recognises nothing; the shared service tokens are unaffected.
 * The startup log states enabled/disabled and the allowlist SIZE only, never a subject, email or audience.
 */
@Configuration
@EnableConfigurationProperties(AdminAuthProperties.class)
public class AdminAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthConfig.class);

    /** The validated trust policy and allowlist; an invalid configuration fails startup here. */
    @Bean
    public HumanAdminSettings humanAdminSettings(AdminAuthProperties properties,
                                                 @Value("${tazzzo.migration.environment:}") String environment) {
        return HumanAdminSettings.from(properties, environment);
    }

    @Bean
    public AdminProfiles adminProfiles(HumanAdminSettings settings) {
        return new AdminProfiles(settings.allowlist());
    }

    @Bean
    public GoogleOidcAuthenticator googleOidcAuthenticator(HumanAdminSettings settings, Clock clock) {
        if (settings.oidc().isEmpty()) {
            log.info("admin_oidc state=disabled");
            return GoogleOidcAuthenticator.disabled();
        }
        GoogleOidcSettings oidc = settings.oidc().get();
        log.info("admin_oidc state=enabled allowlisted_users={}", settings.allowlist().size());
        return new GoogleOidcAuthenticator(new GoogleOidcVerifier(oidc, GoogleOidcVerifier.remoteKeys(oidc), clock),
                settings.allowlist(), oidc.credentialId());
    }
}
