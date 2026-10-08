package com.tazzzo.media;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a SigV4-verifying store does with the adapter's presigned PUT (Versity S3 Gateway, an S3 implementation that
 * checks signatures and, like AWS S3, enforces conditional requests; S3Mock does neither): the upload succeeds only with
 * the signed key, Content-Type, Content-Length AND {@code If-None-Match: *}; a different type, a larger body, a
 * different key, a tampered signature or a dropped precondition is refused by the store itself (403); re-using the URL
 * once the object exists is refused (412), so verified bytes cannot be replaced; and a read pinned to an ETag fails
 * (412) once the object changed. (Scality CloudServer, used earlier, verifies signatures but ignores If-None-Match.)
 */
class S3SignatureEnforcementIT {

    static final String ACCESS = "test-access-key-1";
    static final String SECRET = "test-secret-fixture-1";
    static final String BUCKET = "tazzzo-media-sig";
    @SuppressWarnings("resource")
    /** Pinned by digest (v1.8.0): the tag `latest` drifts. A posix backend inside the container's own /tmp. */
    static final GenericContainer<?> STORE = new GenericContainer<>(DockerImageName.parse(
            "versity/versitygw@sha256:30292fc2eeacc67a36993b01f7a7a5e3361a19cced0e80c1d71cfa2a4b0a2499"))
            .withExposedPorts(7070)
            .withEnv("ROOT_ACCESS_KEY", ACCESS).withEnv("ROOT_SECRET_KEY", SECRET)
            .withCommand("--port", ":7070", "posix", "/tmp")
            .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofMinutes(2));
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};

    static S3Client s3;
    static S3Presigner presigner;
    static S3MediaStorage storage;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() {
        STORE.start();
        URI endpoint = URI.create("http://" + STORE.getHost() + ":" + STORE.getMappedPort(7070));
        StaticCredentialsProvider creds = StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS, SECRET));
        S3Configuration pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        s3 = S3Client.builder().region(Region.US_EAST_1).credentialsProvider(creds).endpointOverride(endpoint)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).serviceConfiguration(pathStyle).build();
        presigner = S3Presigner.builder().region(Region.US_EAST_1).credentialsProvider(creds).endpointOverride(endpoint)
                .serviceConfiguration(pathStyle).build();
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        storage = new S3MediaStorage(s3, presigner, BUCKET, Duration.ofMinutes(5));
    }

    @AfterAll
    static void stop() {
        if (presigner != null) presigner.close();
        if (s3 != null) s3.close();
        STORE.stop();
    }

    @Test
    void the_store_accepts_only_the_signed_key_and_content_type() throws Exception {
        String key = "p/product/TZP-SIG/" + java.util.UUID.randomUUID() + ".png";
        UploadTarget target = storage.createUpload(key, "image/png", PNG.length);

        byte[] larger = new byte[PNG.length + 1];
        System.arraycopy(PNG, 0, larger, 0, PNG.length);
        assertThat(upload(target.url(), larger, "image/png")).as("a larger body than signed").isEqualTo(403);
        assertThat(upload(target.url(), PNG, "image/jpeg")).as("a different Content-Type than signed").isEqualTo(403);
        assertThat(upload(target.url(), PNG, null)).as("no Content-Type").isEqualTo(403);
        assertThat(upload(target.url().replace(key, "p/product/TZP-SIG/other.png"), PNG, "image/png")).as("another key").isEqualTo(403);
        assertThat(upload(target.url().replaceAll("X-Amz-Signature=[0-9a-f]{8}", "X-Amz-Signature=00000000"), PNG, "image/png"))
                .as("a tampered signature").isEqualTo(403);
        assertThat(storage.inspect(key)).isEmpty();
        assertThat(storage.inspect("p/product/TZP-SIG/other.png")).isEmpty();

        assertThat(upload(target.url(), PNG, "image/png")).as("the signed request").isEqualTo(200);
        StoredObject stored = storage.inspect(key).orElseThrow();
        assertThat(stored.sizeBytes()).isEqualTo(PNG.length);
        assertThat(stored.contentType()).isEqualTo("image/png");
        assertThat(MediaSniffer.detect(stored.head())).contains("image/png");
    }

    @Test
    void the_signed_put_is_write_once_so_verified_bytes_cannot_be_replaced() throws Exception {
        String key = "p/product/TZP-SIG/" + java.util.UUID.randomUUID() + ".png";
        UploadTarget target = storage.createUpload(key, "image/png", PNG.length);
        assertThat(upload(target.url(), PNG, "image/png", false)).as("If-None-Match is part of the signature").isEqualTo(403);
        assertThat(upload(target.url(), PNG, "image/png")).as("the first upload").isEqualTo(200);

        byte[] swapped = PNG.clone();
        swapped[15] = 'X';   // same size and type, different bytes
        assertThat(upload(target.url(), swapped, "image/png")).as("re-using the URL after the object exists").isEqualTo(412);
        assertThat(storage.inspect(key).orElseThrow().head()).isEqualTo(PNG);
    }

    @Test
    void inspect_detects_an_object_replaced_between_head_and_read() {
        String key = "p/product/TZP-SIG/" + java.util.UUID.randomUUID() + ".png";
        s3.putObject(b -> b.bucket(BUCKET).key(key).contentType("image/png"), software.amazon.awssdk.core.sync.RequestBody.fromBytes(PNG));
        String etag = s3.headObject(b -> b.bucket(BUCKET).key(key)).eTag();
        byte[] other = PNG.clone();
        other[15] = 'Y';
        s3.putObject(b -> b.bucket(BUCKET).key(key).contentType("image/png"), software.amazon.awssdk.core.sync.RequestBody.fromBytes(other));
        // the read the adapter issues after HEAD, pinned to the HEAD's ETag, must not return the replacement
        assertThatThrownBy(() -> s3.getObject(b -> b.bucket(BUCKET).key(key).range("bytes=0-15").ifMatch(etag)))
                .isInstanceOf(software.amazon.awssdk.services.s3.model.S3Exception.class)
                .satisfies(e -> assertThat(((software.amazon.awssdk.services.s3.model.S3Exception) e).statusCode()).isEqualTo(412));
    }

    private int upload(String url, byte[] bytes, String contentType) throws Exception {
        return upload(url, bytes, contentType, true);
    }

    private int upload(String url, byte[] bytes, String contentType, boolean ifNoneMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        if (contentType != null) b.header("Content-Type", contentType);
        if (ifNoneMatch) b.header("If-None-Match", "*");
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode();
    }
}
