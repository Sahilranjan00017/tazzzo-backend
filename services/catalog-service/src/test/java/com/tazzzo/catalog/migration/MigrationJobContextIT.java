package com.tazzzo.catalog.migration;

import com.tazzzo.catalog.AbstractMongoIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migration job is the application itself started as a one-shot, NON-WEB process
 * ({@code --spring.main.web-application-type=none}, mode DRY_RUN or APPLY). This proves the real application
 * context boots that way and runs the job at startup without error — the exact shape the runbook documents.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "tazzzo.migration.mode=DRY_RUN")
class MigrationJobContextIT extends AbstractMongoIT {

    @Autowired ApplicationContext context;
    @Autowired MigrationProperties properties;

    @Test
    void the_application_starts_as_a_non_web_dry_run_job_without_error() {
        assertThat(context).as("no web server in job mode").isNotInstanceOf(WebApplicationContext.class);
        assertThat(properties.getMode()).isEqualTo(MigrationMode.DRY_RUN);
        assertThat(context.getBean(MigrationRunner.class)).isNotNull();
    }
}
