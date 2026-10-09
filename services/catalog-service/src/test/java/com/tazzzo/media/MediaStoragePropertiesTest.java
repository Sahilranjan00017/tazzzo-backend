package com.tazzzo.media;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The S3 configuration is validated before any client is built, and the message names the key, never a value. */
class MediaStoragePropertiesTest {

    private static MediaStorageProperties.S3 valid() {
        MediaStorageProperties.S3 p = new MediaStorageProperties.S3();
        p.setBucket("tazzzo-media-prod");
        return p;
    }

    @Test
    void aws_defaults_are_valid_and_use_the_default_credential_chain() {
        MediaStorageProperties.S3 p = valid();
        p.validate();
        assertThat(p.getRegion()).isEqualTo("ap-south-1");
        assertThat(p.endpointUri()).isNull();
        assertThat(p.hasStaticCredentials()).isFalse();
        assertThat(p.isPathStyle()).isFalse();
    }

    @Test
    void a_local_store_may_use_http_on_loopback_only() {
        MediaStorageProperties.S3 p = valid();
        p.setEndpoint("http://localhost:9090");
        p.validate();
        p.setEndpoint("http://127.0.0.1:9090");
        p.validate();
        p.setEndpoint("https://s3.ap-south-1.amazonaws.com");
        p.validate();
        p.setEndpoint("http://s3mock:9090");   // a compose service name: single label, never routable
        p.validate();
        p.setEndpoint("http://host.docker.internal:9090");
        p.validate();
        for (String bad : new String[]{"http://storage.example.com", "http://evil.internal:9000", "ftp://localhost:9000",
                "http://user:pw@localhost:9000", "https://user:pw@s3.ap-south-1.amazonaws.com", "not a url", "localhost:9090"}) {
            p.setEndpoint(bad);
            assertThatThrownBy(p::validate).as(bad).isInstanceOf(IllegalStateException.class).hasMessageContaining("endpoint")
                    .hasMessageNotContaining("pw");
        }
    }

    @Test
    void bucket_region_ttl_and_credential_pairs_are_checked() {
        MediaStorageProperties.S3 p = valid();
        p.setBucket(" ");
        assertThatThrownBy(p::validate).hasMessageContaining("bucket");
        p.setBucket("Not_Valid");
        assertThatThrownBy(p::validate).hasMessageContaining("bucket");
        p = valid();
        p.setRegion("");
        assertThatThrownBy(p::validate).hasMessageContaining("region");
        p = valid();
        p.setPresignTtlSeconds(10);
        assertThatThrownBy(p::validate).hasMessageContaining("presign-ttl-seconds");
        p.setPresignTtlSeconds(7200);
        assertThatThrownBy(p::validate).hasMessageContaining("presign-ttl-seconds");
        p = valid();
        p.setAccessKey("only-one-half");
        assertThatThrownBy(p::validate).hasMessageContaining("set together").hasMessageNotContaining("only-one-half");
        p.setSecretKey("fixture-value");
        p.validate();
        assertThat(p.hasStaticCredentials()).isTrue();
    }

    @Test
    void the_provider_is_a_closed_choice() {
        MediaStorageProperties props = new MediaStorageProperties();
        assertThat(props.provider()).isEqualTo(MediaStorageProperties.Provider.DISABLED);
        props.setProvider(" S3 ");
        assertThat(props.provider()).isEqualTo(MediaStorageProperties.Provider.S3);
        props.setProvider("gcs");
        assertThatThrownBy(props::provider).isInstanceOf(IllegalStateException.class).hasMessageContaining("provider");
    }
}
