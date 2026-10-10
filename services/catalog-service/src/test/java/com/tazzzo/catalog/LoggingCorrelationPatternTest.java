package com.tazzzo.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/** The console pattern must carry the MDC ids RequestIdFilter sets (the property is Boot's LOG_CORRELATION_PATTERN slot). */
class LoggingCorrelationPatternTest {

    @Test
    void application_yml_puts_request_id_and_correlation_id_into_every_log_line() throws Exception {
        PropertySource<?> yml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml")).get(0);
        String pattern = String.valueOf(yml.getProperty("logging.pattern.correlation"));
        assertThat(pattern).contains("%X{request_id").contains("%X{correlation_id");
        assertThat(pattern).as("an empty value, not a literal 'null', outside a request").contains(":-}");
    }
}
