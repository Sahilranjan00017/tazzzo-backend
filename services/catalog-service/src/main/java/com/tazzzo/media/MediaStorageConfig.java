package com.tazzzo.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

/**
 * The storage seam's wiring: {@code tazzzo.media.storage.provider} selects {@link DisabledMediaStorage} (default) or
 * {@link S3MediaStorage}; the upload policy and the ingest verifier are provider-independent.
 */
@Configuration
@EnableConfigurationProperties(MediaStorageProperties.class)
class MediaStorageConfig {

    /** The same environment rule as the OTP LOGGING provider and the notification sandbox sender. */
    static final java.util.Set<String> DEV_ENVIRONMENTS = java.util.Set.of("", "local", "test", "dev");

    private static final Logger log = LoggerFactory.getLogger(MediaStorageConfig.class);

    /**
     * Bounds how long an admin media-set write can be held by an unreachable store (inspect runs on the request thread,
     * once per newly referenced asset): each attempt 2s, the whole call (SDK retries included) 5s, then a 503.
     */
    static final ClientOverrideConfiguration CLIENT_TIMEOUTS = ClientOverrideConfiguration.builder()
            .apiCallAttemptTimeout(Duration.ofSeconds(2)).apiCallTimeout(Duration.ofSeconds(5)).build();

    /** Fails startup with the key named when the provider is not one of the closed choices (no silent "no bean"). */
    @Bean
    MediaStorageProperties.Provider mediaStorageProvider(MediaStorageProperties properties) {
        return properties.provider();
    }

    @Bean
    @ConditionalOnProperty(name = "tazzzo.media.storage.provider", havingValue = "disabled", matchIfMissing = true)
    MediaStorage disabledMediaStorage() {
        log.info("media_storage provider=disabled");
        return new DisabledMediaStorage();
    }

    @Bean
    @ConditionalOnProperty(name = "tazzzo.media.storage.provider", havingValue = "s3")
    MediaStorage s3MediaStorage(MediaStorageProperties properties) {
        MediaStorageProperties.S3 p = properties.getS3();
        p.validate();
        AwsCredentialsProvider credentials = p.hasStaticCredentials()
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create(p.getAccessKey(), p.getSecretKey()))
                : DefaultCredentialsProvider.create();
        Region region = Region.of(p.getRegion());
        URI endpoint = p.endpointUri();
        S3Configuration serviceConfig = S3Configuration.builder().pathStyleAccessEnabled(p.isPathStyle()).build();
        S3ClientBuilder client = S3Client.builder().region(region).credentialsProvider(credentials)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).serviceConfiguration(serviceConfig)
                .overrideConfiguration(CLIENT_TIMEOUTS);
        S3Presigner.Builder presigner = S3Presigner.builder().region(region).credentialsProvider(credentials)
                .serviceConfiguration(serviceConfig);
        if (endpoint != null) {
            client.endpointOverride(endpoint);
            presigner.endpointOverride(endpoint);
        }
        // never a credential value: bucket, region, endpoint host and the credential SOURCE only
        log.info("media_storage provider=s3 bucket={} region={} endpoint={} path_style={} credentials={}", p.getBucket(),
                p.getRegion(), endpoint == null ? "aws" : endpoint.getHost(), p.isPathStyle(),
                p.hasStaticCredentials() ? "static" : "default-chain");
        return new S3MediaStorage(client.build(), presigner.build(), p.getBucket(), Duration.ofSeconds(p.getPresignTtlSeconds()));
    }

    @Bean
    MediaUploadPolicy mediaUploadPolicy(@Value("${tazzzo.media.max-upload-bytes:" + MediaUploadPolicy.DEFAULT_MAX_BYTES + "}") long maxBytes) {
        return new MediaUploadPolicy(maxBytes);
    }

    @Bean
    MediaMetrics mediaMetrics(io.micrometer.core.instrument.MeterRegistry registry) {
        return new MediaMetrics(registry);
    }

    @Bean
    MediaIngestVerifier mediaIngestVerifier(MediaStorage storage, MediaUploadPolicy policy,
                                            @Value("${tazzzo.media.max-pixels:" + MediaIngestVerifier.DEFAULT_MAX_PIXELS + "}") long maxPixels,
                                            @Value("${tazzzo.media.max-dimension:" + MediaAsset.MAX_DIMENSION + "}") int maxDimension,
                                            @Value("${tazzzo.migration.environment:}") String environment,
                                            MediaMetrics metrics) {
        boolean devLike = DEV_ENVIRONMENTS.contains(environment == null ? "" : environment.trim());
        if (!storage.enabled()) {
            log.warn("media_verification disabled: no storage configured; new media references are {} in environment '{}'",
                    devLike ? "accepted UNVERIFIED" : "REFUSED", environment == null ? "" : environment.trim());
        }
        return new MediaIngestVerifier(storage, policy, maxPixels, maxDimension, devLike, metrics);
    }
}
