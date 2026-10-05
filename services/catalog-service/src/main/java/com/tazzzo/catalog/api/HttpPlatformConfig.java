package com.tazzzo.catalog.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * Platform HTTP baseline wiring.
 *
 * <p><b>CORS</b> is a browser concern only (the mobile app and a server-side BFF are not subject to it) and is OFF unless
 * {@code tazzzo.http.cors.allowed-origins} lists exact origins. When it is on, the policy is: those origins only (never
 * a wildcard, never reflected), the API's methods, the request headers this API actually reads, no credentials
 * (bearer tokens travel in the Authorization header, never cookies), and the response headers a client needs to read.
 * A pre-flight from any other origin is refused (403) before any application filter runs.
 *
 * <p><b>Forwarded headers</b> are NOT applied to the request ({@code server.forward-headers-strategy=none}): the
 * application never rewrites scheme, host or client address from {@code X-Forwarded-*}. The one consumer of
 * {@code X-Forwarded-For} is the rate limiter's {@code ClientIpResolver}, which honours it only from the configured
 * trusted-proxy CIDRs and fails closed otherwise. Nothing in this service builds absolute URLs from the request.
 */
@Configuration
@EnableConfigurationProperties(HttpPlatformProperties.class)
public class HttpPlatformConfig {

    static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    static final List<String> ALLOWED_HEADERS = List.of("Authorization", "Content-Type", "Accept", "If-Match",
            "If-None-Match", "Idempotency-Key", RequestIdFilter.CORRELATION_HEADER, "X-Installation-Id");
    static final List<String> EXPOSED_HEADERS = List.of("X-Request-Id", RequestIdFilter.CORRELATION_HEADER, "ETag",
            "Retry-After", "Location");

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(HttpPlatformProperties properties) {
        properties.validate();
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(corsSource(properties)));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2); // after request-id and body-limit, before any auth filter
        registration.setEnabled(properties.getCors().enabled());
        return registration;
    }

    static UrlBasedCorsConfigurationSource corsSource(HttpPlatformProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.getCors().normalizedOrigins()); // exact strings, no patterns
        config.setAllowedMethods(ALLOWED_METHODS);
        config.setAllowedHeaders(ALLOWED_HEADERS);
        config.setExposedHeaders(EXPOSED_HEADERS);
        config.setAllowCredentials(false);
        config.setMaxAge(properties.getCors().getMaxAgeSeconds());
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
