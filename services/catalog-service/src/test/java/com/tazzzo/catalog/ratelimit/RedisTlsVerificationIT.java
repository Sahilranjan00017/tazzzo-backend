package com.tazzzo.catalog.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The limiter's own Valkey/Redis connection over TLS ({@code rediss://}) must verify both the server certificate and
 * the host name. CVE-2026-50010 (Netty before 4.1.135) silently dropped hostname verification when a plain
 * {@code X509TrustManager} was wrapped; this client never supplies a trust manager (it uses the JDK default, an
 * {@code X509ExtendedTrustManager}, with peer verification left on), so that path is not taken. These tests prove the
 * resulting behaviour against a real TLS Redis instead of assuming it, through the production factory
 * {@link ConsumerRateLimitConfig.RedisMode#consumerRateLimitRedisTemplate}:
 * an untrusted certificate is refused, a trusted certificate for another host name is refused, and a trusted
 * certificate for the connected host is accepted (the control that makes the two refusals meaningful).
 *
 * <p>The CA and the server certificate (valid for {@code localhost} only) are generated per run with the JDK's
 * {@code keytool}; no key material is committed. Certificate validation is never disabled.
 */
class RedisTlsVerificationIT {

    static final String PASS = "changeit";
    static Path dir;
    static GenericContainer<?> redis;

    @BeforeAll
    static void startTlsRedis() throws Exception {
        dir = Files.createTempDirectory("tazzzo-redis-tls");
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=tazzzo-test-ca",
                "-ext", "bc:c", "-validity", "2", "-keystore", p("ca.p12"), "-storetype", "PKCS12", "-storepass", PASS);
        keytool("-exportcert", "-alias", "ca", "-keystore", p("ca.p12"), "-storepass", PASS, "-rfc", "-file", p("ca.crt"));
        keytool("-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost",
                "-validity", "2", "-keystore", p("server.p12"), "-storetype", "PKCS12", "-storepass", PASS);
        keytool("-certreq", "-alias", "server", "-keystore", p("server.p12"), "-storepass", PASS, "-file", p("server.csr"));
        keytool("-gencert", "-alias", "ca", "-keystore", p("ca.p12"), "-storepass", PASS, "-infile", p("server.csr"),
                "-outfile", p("server.crt"), "-rfc", "-ext", "SAN=dns:localhost", "-ext", "EKU=serverAuth", "-validity", "2");
        keytool("-importcert", "-noprompt", "-alias", "tazzzo-test-ca", "-file", p("ca.crt"), "-keystore", p("trust.p12"),
                "-storetype", "PKCS12", "-storepass", PASS);
        KeyStore server = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(dir.resolve("server.p12"))) {
            server.load(in, PASS.toCharArray());
        }
        PrivateKey key = (PrivateKey) server.getKey("server", PASS.toCharArray());
        Files.writeString(dir.resolve("server.key"), pem("PRIVATE KEY", key.getEncoded()));

        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("server.crt"), 0644), "/tls/server.crt")
                .withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("server.key"), 0644), "/tls/server.key")
                .withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("ca.crt"), 0644), "/tls/ca.crt")
                .withCommand("redis-server", "--port", "0", "--tls-port", "6379",
                        "--tls-cert-file", "/tls/server.crt", "--tls-key-file", "/tls/server.key",
                        "--tls-ca-cert-file", "/tls/ca.crt", "--tls-auth-clients", "no")
                .withExposedPorts(6379)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));
        redis.start();
    }

    @AfterAll
    static void stop() throws Exception {
        if (redis != null) {
            redis.stop();
        }
        if (dir != null) {
            try (var files = Files.list(dir)) {
                for (Path f : files.toList()) {
                    Files.deleteIfExists(f);
                }
            }
            Files.deleteIfExists(dir);
        }
    }

    private static String p(String name) {
        return dir.resolve(name).toString();
    }

    private static void keytool(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("keytool " + args[0] + ": " + output).isZero();
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    /** The limiter's connection exactly as production builds it, pointed at the TLS Redis through {@code host}. */
    private static StringRedisTemplate limiterTemplate(String host) {
        ConsumerRateLimitProperties properties = new ConsumerRateLimitProperties();
        properties.setMode("REDIS");
        properties.setRedisUrl("rediss://" + host + ":" + redis.getMappedPort(6379));
        properties.getIp().setCapacity(10);
        properties.getIp().setRefillPerSecond(1);
        properties.getInstallation().setCapacity(10);
        properties.getInstallation().setRefillPerSecond(1);
        properties.setTrustedProxyCidrs(List.of("10.0.0.0/8"));
        return new ConsumerRateLimitConfig.RedisMode().consumerRateLimitRedisTemplate(properties);
    }

    private static String ping(StringRedisTemplate template) {
        return template.execute((RedisCallback<String>) connection -> connection.ping());
    }

    private static void close(StringRedisTemplate template) {
        ((LettuceConnectionFactory) template.getConnectionFactory()).destroy();
    }

    /** Runs {@code body} with the JVM default trust store replaced by one holding only the test CA, then restores it. */
    private static void trustingTheTestCa(Runnable body) {
        String[] keys = {"javax.net.ssl.trustStore", "javax.net.ssl.trustStoreType", "javax.net.ssl.trustStorePassword"};
        String[] previous = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            previous[i] = System.getProperty(keys[i]);
        }
        System.setProperty(keys[0], p("trust.p12"));
        System.setProperty(keys[1], "PKCS12");
        System.setProperty(keys[2], PASS);
        try {
            body.run();
        } finally {
            for (int i = 0; i < keys.length; i++) {
                if (previous[i] == null) {
                    System.clearProperty(keys[i]);
                } else {
                    System.setProperty(keys[i], previous[i]);
                }
            }
        }
    }

    private static String causeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getClass().getName()).append(": ").append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    @Test
    void the_limiter_connection_uses_tls_with_peer_verification_on() {
        StringRedisTemplate template = limiterTemplate("localhost");
        try {
            LettuceConnectionFactory factory = (LettuceConnectionFactory) template.getConnectionFactory();
            assertThat(factory.getClientConfiguration().isUseSsl()).isTrue();
            assertThat(factory.getClientConfiguration().isVerifyPeer()).isTrue();
        } finally {
            close(template);
        }
    }

    @Test
    void a_server_certificate_from_an_untrusted_ca_is_refused() {
        StringRedisTemplate template = limiterTemplate("localhost");
        try {
            String chain = causeChain(catchThrowable(() -> ping(template)));
            assertThat(chain).contains("SSLHandshakeException").containsIgnoringCase("certification path");
        } finally {
            close(template);
        }
    }

    @Test
    void a_trusted_certificate_for_another_host_name_is_refused() {
        trustingTheTestCa(() -> {
            StringRedisTemplate template = limiterTemplate("127.0.0.1");   // the certificate names only localhost
            try {
                String chain = causeChain(catchThrowable(() -> ping(template)));
                assertThat(chain).contains("SSLHandshakeException").containsIgnoringCase("subject alternative names");
            } finally {
                close(template);
            }
        });
    }

    @Test
    void a_trusted_certificate_for_the_connected_host_is_accepted() {
        trustingTheTestCa(() -> {
            StringRedisTemplate template = limiterTemplate("localhost");
            try {
                assertThat(ping(template)).isEqualTo("PONG");
            } finally {
                close(template);
            }
        });
    }
}
