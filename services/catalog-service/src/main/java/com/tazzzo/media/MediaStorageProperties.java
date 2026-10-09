package com.tazzzo.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/**
 * {@code tazzzo.media.storage.*}: which object store holds product images, and how to reach it.
 *
 * <p>{@code provider} is {@code disabled} (the default: uploads refused, media sets metadata-only) or {@code s3}. For
 * {@code s3}: the bucket is required; {@code region} defaults to the production region; {@code endpoint} and
 * {@code path-style} exist for an S3-compatible store (MinIO locally and in tests) and are unset for AWS; credentials
 * come from the default provider chain (the ECS task role) unless BOTH {@code access-key} and {@code secret-key} are
 * set, which is meant for a local store only. Secrets are never logged.
 */
@ConfigurationProperties(prefix = "tazzzo.media.storage")
public class MediaStorageProperties {

    public enum Provider { DISABLED, S3 }

    private String provider = "disabled";
    private final S3 s3 = new S3();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public S3 getS3() {
        return s3;
    }

    public Provider provider() {
        try {
            return Provider.valueOf(provider == null ? "DISABLED" : provider.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("tazzzo.media.storage.provider must be disabled or s3");
        }
    }

    public static class S3 {
        private String bucket = "";
        private String region = "ap-south-1";
        private String endpoint = "";
        private boolean pathStyle;
        private int presignTtlSeconds = 300;
        private String accessKey = "";
        private String secretKey = "";

        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public boolean isPathStyle() { return pathStyle; }
        public void setPathStyle(boolean pathStyle) { this.pathStyle = pathStyle; }
        public int getPresignTtlSeconds() { return presignTtlSeconds; }
        public void setPresignTtlSeconds(int presignTtlSeconds) { this.presignTtlSeconds = presignTtlSeconds; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

        /** True when explicit (local-store) credentials are configured; otherwise the default provider chain is used. */
        public boolean hasStaticCredentials() {
            return accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank();
        }

        /** @throws IllegalStateException the endpoint is not a URI at all */
        public URI endpointUri() {
            if (endpoint == null || endpoint.isBlank()) return null;
            try {
                return URI.create(endpoint.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("tazzzo.media.storage.s3.endpoint must be an absolute http(s) URL");
            }
        }

        /** @throws IllegalStateException a configuration that cannot work; the message names the key, never a value */
        public void validate() {
            if (bucket == null || bucket.isBlank() || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
                throw new IllegalStateException("tazzzo.media.storage.s3.bucket must be a valid bucket name");
            }
            if (region == null || region.isBlank()) {
                throw new IllegalStateException("tazzzo.media.storage.s3.region is required");
            }
            if (presignTtlSeconds < 30 || presignTtlSeconds > 3600) {
                throw new IllegalStateException("tazzzo.media.storage.s3.presign-ttl-seconds must be within 30..3600");
            }
            if ((accessKey != null && !accessKey.isBlank()) != (secretKey != null && !secretKey.isBlank())) {
                throw new IllegalStateException("tazzzo.media.storage.s3.access-key and secret-key must be set together");
            }
            URI uri = endpointUri();
            if (uri != null) {
                boolean https = "https".equals(uri.getScheme());
                if (!(https || "http".equals(uri.getScheme())) || uri.getHost() == null) {
                    throw new IllegalStateException("tazzzo.media.storage.s3.endpoint must be an absolute http(s) URL");
                }
                if (uri.getUserInfo() != null) {
                    throw new IllegalStateException("tazzzo.media.storage.s3.endpoint must not carry credentials");
                }
                if (!https && !isLocalStoreHost(uri.getHost())) {
                    throw new IllegalStateException("tazzzo.media.storage.s3.endpoint must use https unless it is a local store");
                }
            }
        }

        /** Loopback, Docker Desktop's host alias, or a single-label name (a compose service): never a routable domain. */
        static boolean isLocalStoreHost(String host) {
            String h = host.toLowerCase(java.util.Locale.ROOT);
            return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1")
                    || h.equals("host.docker.internal") || !h.contains(".");
        }
    }
}
