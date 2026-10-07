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

/**
 * What a SigV4-verifying store does with the adapter's presigned PUT (Scality CloudServer, a real S3 implementation
 * that checks signatures, unlike S3Mock): the upload succeeds only with the signed key, Content-Type AND Content-Length;
 * a different type, a larger body, a different key, or a tampered signature is refused by the store itself (403), and
 * the stored Content-Type is the signed one.
 */
class S3SignatureEnforcementIT {

    static final String ACCESS = "test-access-key-1";
    static final String SECRET = "test-secret-fixture-1";
    static final String BUCKET = "tazzzo-media-sig";
    @SuppressWarnings("resource")
    /** Pinned by digest: the tag `latest` drifts. */
    static final GenericContainer<?> STORE = new GenericContainer<>(DockerImageName.parse(
            "zenko/cloudserver@sha256:b53e57829cf7df357323e60a19c9f98d2218f1b7ccb1d7cea5761a5a227a9ee3"))
            .withExposedPorts(8000)
            .withEnv("S3BACKEND", "mem").withEnv("REMOTE_MANAGEMENT_DISABLE", "1")
            .withEnv("SCALITY_ACCESS_KEY_ID", ACCESS).withEnv("SCALITY_SECRET_ACCESS_KEY", SECRET)
            .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofMinutes(2));
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};

    static S3Client s3;
    static S3Presigner presigner;
    static S3MediaStorage storage;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() {
        STORE.start();
        URI endpoint = URI.create("http://" + STORE.getHost() + ":" + STORE.getMappedPort(8000));
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

    private int upload(String url, byte[] bytes, String contentType) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        if (contentType != null) b.header("Content-Type", contentType);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode();
    }
}
