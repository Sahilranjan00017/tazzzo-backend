package com.tazzzo.location;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the disabled resolver unless a provider module contributes its own {@link GeoPincodeResolver} bean. */
@Configuration
class GeoConfig {

    @Bean
    @ConditionalOnMissingBean(GeoPincodeResolver.class)
    GeoPincodeResolver disabledGeoPincodeResolver() {
        return new DisabledGeoPincodeResolver();
    }
}
