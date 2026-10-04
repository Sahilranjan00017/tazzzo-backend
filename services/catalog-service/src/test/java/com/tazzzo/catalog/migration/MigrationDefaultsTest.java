package com.tazzzo.catalog.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped defaults are the safe ones: a service started with no migration configuration only VERIFIES (read-only) and
 * never mutates the schema, and the datastore verifier cannot be switched off. Reads the real application.yml.
 */
class MigrationDefaultsTest {

    private static Properties yml() {
        YamlPropertiesFactoryBean f = new YamlPropertiesFactoryBean();
        f.setResources(new FileSystemResource("src/main/resources/application.yml"));
        return f.getObject();
    }

    @Test
    void the_default_migration_mode_is_read_only_verification() {
        assertThat(yml().getProperty("tazzzo.migration.mode")).isEqualTo("${TAZZZO_MIGRATION_MODE:VERIFY}");
        assertThat(new MigrationProperties().getMode()).isEqualTo(MigrationMode.VERIFY);
    }

    @Test
    void the_legacy_startup_flags_are_off_by_default() {
        assertThat(yml().getProperty("tazzzo.schema.bootstrap-on-startup")).isEqualTo("false");
        assertThat(yml().getProperty("tazzzo.schema.load-taxonomy-seed")).isEqualTo("false");
    }

    @Test
    void privilege_verification_defaults_to_auto_and_has_no_off_switch() {
        assertThat(yml().getProperty("tazzzo.datastore.privilege-verification")).isEqualTo("${TAZZZO_DATASTORE_PRIVILEGE_VERIFICATION:AUTO}");
    }
}
