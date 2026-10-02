package com.tazzzo.admin.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    @Bean
    public GoogleOidcAuthenticator googleOidcAuthenticator(AdminAuthProperties properties, Clock clock) {
        HumanAdminSettings settings = HumanAdminSettings.from(properties);
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
