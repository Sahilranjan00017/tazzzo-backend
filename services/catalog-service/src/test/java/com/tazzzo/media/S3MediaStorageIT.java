package com.tazzzo.media;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link S3MediaStorage} against a real S3-compatible store in a container (Adobe S3Mock): the presigned PUT really
 * uploads, {@link S3MediaStorage#inspect} really reads what was stored, and the presigned URL carries the signature
 * contract AWS S3 enforces (the signature covers {@code content-type}, so a different type is refused by a store that
 * verifies SigV4; S3Mock does not, which is why that refusal is a staging-verification item, not a local assertion).
 */
class S3MediaStorageIT {

    /** The same image the runbook names for local development. S3Mock ignores credentials and verifies no signature. */
    static final DockerImageName S3MOCK = DockerImageName.parse("adobe/s3mock:3.11.0");
    static final String BUCKET = "tazzzo-media-it";
    @SuppressWarnings("resource")
    static final GenericContainer<?> STORE = new GenericContainer<>(S3MOCK).withExposedPorts(9090).withEnv("initialBuckets", BUCKET);
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};

    static S3Client s3;
    static S3Presigner presigner;
    static S3MediaStorage storage;
    final HttpClient http = HttpClient.newHttpClient();

    static String endpoint() {
        return "http://" + STORE.getHost() + ":" + STORE.getMappedPort(9090);
    }

    @BeforeAll
    static void start() {
        STORE.start();
        StaticCredentialsProvider creds = StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access", "test-secret"));
        S3Configuration pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        s3 = S3Client.builder().region(Region.US_EAST_1).credentialsProvider(creds).endpointOverride(URI.create(endpoint()))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).serviceConfiguration(pathStyle).build();
        presigner = S3Presigner.builder().region(Region.US_EAST_1).credentialsProvider(creds)
                .endpointOverride(URI.create(endpoint())).serviceConfiguration(pathStyle).build();
        storage = new S3MediaStorage(s3, presigner, BUCKET, Duration.ofMinutes(5));
    }

    @AfterAll
    static void stop() {
        if (presigner != null) presigner.close();
        if (s3 != null) s3.close();
        STORE.stop();
    }

    @Test
    void presigned_put_uploads_and_inspect_reads_size_type_and_magic_bytes() throws Exception {
        String key = "p/product/TZP-1/" + java.util.UUID.randomUUID() + ".png";
        UploadTarget target = storage.createUpload(key, "image/png", PNG.length);
        assertThat(target.method()).isEqualTo("PUT");
        assertThat(target.expiresAt()).isAfter(Instant.now().plusSeconds(200));
        assertThat(target.headers()).as("one canonical spelling per signed header, host excluded")
                .containsOnlyKeys("Content-Type", "Content-Length", "If-None-Match").containsEntry("If-None-Match", "*")
                .containsEntry("Content-Type", "image/png").containsEntry("Content-Length", String.valueOf(PNG.length));

        assertThat(storage.inspect(key)).as("nothing stored before the upload").isEmpty();
        assertThat(upload(target, PNG)).isEqualTo(200);

        Optional<StoredObject> stored = storage.inspect(key);
        assertThat(stored).isPresent();
        assertThat(stored.get().sizeBytes()).isEqualTo(PNG.length);
        // S3Mock may not persist the Content-Type of a presigned PUT (AWS S3 and Versity do: see S3SignatureEnforcementIT).
        // The verifier sniffs the bytes AND requires a reported stored type to match them, so a mislabelled object is refused.
        assertThat(stored.get().contentType()).isIn("image/png", "application/octet-stream");
        assertThat(stored.get().head()).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        assertThat(MediaSniffer.detect(stored.get().head())).contains("image/png");
    }

    /**
     * The signature contract: the URL is a SigV4 presign for exactly this bucket/key whose signed headers include
     * {@code content-type}, so AWS S3 refuses an upload with a different type, key or an expired signature. The secret
     * never appears in the URL.
     */
    @Test
    void the_presigned_url_binds_key_and_content_type() {
        String key = "p/product/TZP-2/" + java.util.UUID.randomUUID() + ".png";
        UploadTarget target = storage.createUpload(key, "image/png", 1234);
        String url = target.url();
        assertThat(url).startsWith(endpoint() + "/" + BUCKET + "/" + key + "?");
        assertThat(url).contains("X-Amz-Algorithm=AWS4-HMAC-SHA256").contains("X-Amz-Expires=300")
                .contains("X-Amz-Credential=test-access").contains("X-Amz-Signature=");
        assertThat(url.toLowerCase(java.util.Locale.ROOT)).contains("x-amz-signedheaders=content-length%3bcontent-type%3bhost");
        assertThat(url).doesNotContain("test-secret");
        assertThat(target.headers().keySet()).map(String::toLowerCase).contains("content-type").doesNotContain("host");
    }

    @Test
    void inspect_reads_only_the_leading_bytes_of_a_large_object() throws Exception {
        String key = "p/product/TZP-4/" + java.util.UUID.randomUUID() + ".png";
        byte[] big = new byte[200_000];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertThat(upload(storage.createUpload(key, "image/png", big.length), big)).isEqualTo(200);
        StoredObject stored = storage.inspect(key).orElseThrow();
        assertThat(stored.sizeBytes()).isEqualTo(big.length);
        assertThat(stored.head().length).isEqualTo(S3MediaStorage.HEAD_BYTES);
    }

    @Test
    void an_empty_object_is_reported_with_no_bytes_and_cannot_pass_the_sniffer() {
        String key = "p/product/TZP-5/" + java.util.UUID.randomUUID() + ".png";
        s3.putObject(software.amazon.awssdk.services.s3.model.PutObjectRequest.builder().bucket(BUCKET).key(key).build(),
                software.amazon.awssdk.core.sync.RequestBody.empty());
        StoredObject stored = storage.inspect(key).orElseThrow();
        assertThat(stored.sizeBytes()).isZero();
        assertThat(stored.head()).isEmpty();
        assertThat(MediaSniffer.detect(stored.head())).isEmpty();
    }

    /** An unreachable store is an outage ({@link MediaStorageFailure}), never "not found" and never a raw SDK error. */
    @Test
    void an_unreachable_store_is_an_outage_not_a_missing_object() {
        StaticCredentialsProvider creds = StaticCredentialsProvider.create(AwsBasicCredentials.create("x", "y"));
        URI closed = URI.create("http://127.0.0.1:1");
        try (S3Client dead = S3Client.builder().region(Region.US_EAST_1).credentialsProvider(creds).endpointOverride(closed)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(b -> b.retryStrategy(software.amazon.awssdk.retries.DefaultRetryStrategy.doNotRetry()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
            S3MediaStorage unreachable = new S3MediaStorage(dead, presigner, BUCKET, Duration.ofMinutes(5));
            assertThatThrownBy(() -> unreachable.inspect("p/product/TZP-6/x.png")).isInstanceOf(MediaStorageFailure.class)
                    .hasMessageNotContaining("127.0.0.1").hasMessageNotContaining(BUCKET);
        }
    }

    @Test
    void unsafe_keys_and_bad_configuration_are_refused() {
        assertThatThrownBy(() -> storage.createUpload("../etc/passwd", "image/png", 1)).isInstanceOf(InvalidMediaException.class);
        assertThatThrownBy(() -> storage.createUpload("p/x.png", null, 1)).isInstanceOf(InvalidMediaException.class);
        assertThatThrownBy(() -> storage.createUpload("p/x.png", "image/png", 0)).isInstanceOf(InvalidMediaException.class);
        assertThat(storage.inspect("../etc/passwd")).isEmpty();
        assertThat(storage.inspect("p/product/TZP-9/missing.png")).isEmpty();
        assertThatThrownBy(() -> new S3MediaStorage(s3, presigner, " ", Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new S3MediaStorage(s3, presigner, BUCKET, Duration.ofSeconds(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new S3MediaStorage(s3, presigner, BUCKET, Duration.ofHours(2))).isInstanceOf(IllegalArgumentException.class);
    }

    private int upload(UploadTarget target, byte[] bytes) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(target.url()))
                .method(target.method(), HttpRequest.BodyPublishers.ofByteArray(bytes));
        target.headers().forEach((k, v) -> {
            if (!k.equalsIgnoreCase("Content-Length")) b.header(k, v);   // the JDK client sets it from the body; must match
        });
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode();
    }
}
