package com.tazzzo.catalog.api;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link AcceptNegotiationInterceptor} for every route. It uses Spring MVC's own content-negotiation manager
 * (resolved lazily: the manager is built by the same MVC configuration that collects this configurer), so the up-front
 * decision reads {@code Accept} exactly as the response writer would.
 */
@Configuration
public class AcceptNegotiationConfig implements WebMvcConfigurer {

    private final ObjectProvider<ContentNegotiationManager> negotiation;

    public AcceptNegotiationConfig(
            @Qualifier("mvcContentNegotiationManager") ObjectProvider<ContentNegotiationManager> negotiation) {
        this.negotiation = negotiation;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AcceptNegotiationInterceptor(negotiation::getObject));
    }
}
