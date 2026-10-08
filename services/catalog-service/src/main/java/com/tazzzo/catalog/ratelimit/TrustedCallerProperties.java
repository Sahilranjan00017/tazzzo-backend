package com.tazzzo.catalog.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code tazzzo.consumer.trusted-callers}: the server-side callers (today: the Next.js storefront) that may
 * present a shared secret and be admitted against their OWN bucket instead of their egress IP's.
 *
 * <p><b>No default, by design.</b> Empty means "no trusted caller": every request is admitted exactly as
 * before. Entries come from the environment ({@code TAZZZO_CONSUMER_TRUSTEDCALLERS_0_NAME},
 * {@code TAZZZO_CONSUMER_TRUSTEDCALLERS_0_SECRET}, ...), never from a checked-in file. The secret is
 * validated at startup and then reduced to a digest by {@link TrustedCallerResolver}; it is never logged,
 * and {@link Caller#toString()} omits it.
 */
@ConfigurationProperties(prefix = "tazzzo.consumer")
public class TrustedCallerProperties {

    private List<Caller> trustedCallers = new ArrayList<>();

    public List<Caller> getTrustedCallers() {
        return trustedCallers;
    }

    public void setTrustedCallers(List<Caller> trustedCallers) {
        this.trustedCallers = trustedCallers;
    }

    public static class Caller {
        /** Bounded, lowercase label: the bucket key suffix and a metric tag value. Not secret. */
        private String name;
        /** Shared secret, at least {@link TrustedCallerResolver#MIN_SECRET_LENGTH} characters. */
        private String secret;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        /** Never the secret. */
        @Override
        public String toString() {
            return "Caller[name=" + name + "]";
        }
    }
}
