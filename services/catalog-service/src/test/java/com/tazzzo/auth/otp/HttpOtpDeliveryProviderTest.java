package com.tazzzo.auth.otp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The gateway adapter against a loopback server: payload, credential, outcomes, redirects, timeouts, and silence. */
@org.junit.jupiter.api.Timeout(15)   // a gateway call with no bound must FAIL a test, never hang the build
class HttpOtpDeliveryProviderTest {

    static final String SECRET = "Bearer SUPER-SECRET-GATEWAY-TOKEN-123";
    static final String OTP = "482916";
    static final Phone PHONE = new Phone("+919876543210");

    record Received(String method, String auth, String contentType, String body) { }

    HttpServer server;
    HttpServer other;
    final List<Received> received = new CopyOnWriteArrayList<>();
    final AtomicInteger otherHits = new AtomicInteger();
    ListAppender<ILoggingEvent> logs;
    SimpleMeterRegistry registry;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        other = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        other.createContext("/", ex -> {
            otherHits.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        other.start();
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger("com.tazzzo.auth.otp")).addAppender(logs);
        ((Logger) LoggerFactory.getLogger("com.tazzzo.auth.otp")).setLevel(Level.DEBUG);
        registry = new SimpleMeterRegistry();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        other.stop(0);
        ((Logger) LoggerFactory.getLogger("com.tazzzo.auth.otp")).detachAppender(logs);
    }

    private void respond(Consumer<HttpExchange> handler) {
        server.createContext("/send", ex -> {
            received.add(new Received(ex.getRequestMethod(), ex.getRequestHeaders().getFirst("Authorization"),
                    ex.getRequestHeaders().getFirst("Content-Type"), new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            handler.accept(ex);
            ex.close();
        });
        server.start();
    }

    private static void status(HttpExchange ex, int code) {
        try {
            ex.sendResponseHeaders(code, -1);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private HttpOtpDeliveryProvider provider(String path, java.util.function.Consumer<HttpOtpGatewayProperties> tweak) {
        HttpOtpGatewayProperties p = new HttpOtpGatewayProperties();
        p.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + path);
        p.setAuthHeaderValue(SECRET);
        p.setSender("TAZZZO");
        p.setReadTimeoutMillis(600);
        p.setConnectTimeoutMillis(300);
        tweak.accept(p);
        return new HttpOtpDeliveryProvider(p, 5, registry);
    }

    private double count(String outcome) {
        var c = registry.find("otp_gateway_send").tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    private void assertSilent(Throwable thrown) {
        List<String> everything = new java.util.ArrayList<>();
        everything.add(String.valueOf(thrown.getMessage()));
        logs.list.forEach(e -> everything.add(e.getFormattedMessage()));
        for (String text : everything) {
            assertThat(text).doesNotContain(OTP).doesNotContain("9876543210").doesNotContain("SUPER-SECRET").doesNotContain("127.0.0.1");
        }
    }

    @Test
    void an_accepted_send_posts_the_documented_json_with_the_credential_and_counts_accepted() {
        respond(ex -> status(ex, 202));
        provider("/send", p -> { }).sendLoginOtp(PHONE, OTP, Duration.ofSeconds(300));
        assertThat(received).hasSize(1);
        Received r = received.get(0);
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.auth()).isEqualTo(SECRET);
        assertThat(r.contentType()).isEqualTo("application/json");
        assertThat(r.body()).isEqualTo("{\"to\":\"+919876543210\",\"message\":\"482916 is your Tazzzo verification code. It expires in 5 minutes. Do not share it with anyone.\",\"sender\":\"TAZZZO\"}");
        assertThat(count("accepted")).isEqualTo(1);
        assertThat(logs.list).as("a success logs nothing about the message").noneMatch(e -> e.getFormattedMessage().contains(OTP));
    }

    @Test
    void the_template_is_rendered_and_json_escaped_and_the_sender_is_optional() {
        respond(ex -> status(ex, 200));
        provider("/send", p -> {
            p.setMessageTemplate("Code {otp} \"quoted\" \\ valid {minutes} min");
            p.setSender(null);
        }).sendLoginOtp(PHONE, OTP, Duration.ofSeconds(61));
        assertThat(received.get(0).body()).isEqualTo("{\"to\":\"+919876543210\",\"message\":\"Code 482916 \\\"quoted\\\" \\\\ valid 2 min\"}");
    }

    @Test
    void a_rejection_is_a_fixed_provider_exception_that_leaks_nothing_and_is_counted() {
        respond(ex -> status(ex, 500));
        HttpOtpDeliveryProvider p = provider("/send", t -> { });
        Throwable thrown = org.junit.jupiter.api.Assertions.assertThrows(OtpProviderException.class, () -> p.sendLoginOtp(PHONE, OTP, Duration.ofSeconds(300)));
        assertThat(thrown.getMessage()).isEqualTo("gateway rejected the message");
        assertSilent(thrown);
        assertThat(count("rejected")).isEqualTo(1);
        assertThat(logs.list).anyMatch(e -> e.getFormattedMessage().contains("outcome=REJECTED"));
    }

    @Test
    void a_client_error_status_is_also_a_rejection() {
        respond(ex -> status(ex, 401));
        assertThatThrownBy(() -> provider("/send", t -> { }).sendLoginOtp(PHONE, OTP, Duration.ofSeconds(300))).isInstanceOf(OtpProviderException.class);
        assertThat(count("rejected")).isEqualTo(1);
    }

    @Test
    void a_redirect_is_never_followed_and_the_other_host_never_sees_the_code_or_credential() {
        respond(ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + other.getAddress().getPort() + "/steal");
            status(ex, 302);
        });
        HttpOtpDeliveryProvider p = provider("/send", t -> { });
        Throwable thrown = org.junit.jupiter.api.Assertions.assertThrows(OtpProviderException.class, () -> p.sendLoginOtp(PHONE, OTP, Duration.ofSeconds(300)));
        assertThat(thrown.getMessage()).isEqualTo("gateway answered a redirect");
        assertThat(otherHits.get()).as("the redirect target was never contacted").isZero();
        assertThat(count("redirect")).isEqualTo(1);
        assertSilent(thrown);
    }

    @Test
    void a_slow_gateway_times_out_within_the_bound() {
        respond(ex -> {
            try {
                Thread.sleep(3000);
                status(ex, 200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        HttpOtpDeliveryProvider p = provider("/send", t -> t.setReadTimeoutMillis(300));
        long started = System.nanoTime();
        Throwable thrown = org.junit.jupiter.api.Assertions.assertThrows(OtpProviderException.class, () -> p.sendLoginOtp(PHONE, OTP, Duration.ofSeconds(300)));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(thrown.getMessage()).isEqualTo("gateway timed out");
        assertThat(count("timeout")).isEqualTo(1);
        assertSilent(thrown);
    }

    @Test
    void an_unreachable_gateway_is_a_transport_error_without_leaking_the_address() throws IOException {
        int port = server.getAddress().getPort();
        server.stop(0);   // nothing listens now
        HttpOtpGatewayProperties props = new HttpOtpGatewayProperties();
        props.setUrl("http://127.0.0.1:" + port + "/send");
        props.setAuthHeaderValue(SECRET);
        HttpOtpDeliveryProvider p = new HttpOtpDeliveryProvider(props, 10, registry);
        Throwable thrown = org.junit.jupiter.api.Assertions.assertThrows(OtpProviderException.class, () -> p.sendLoginOtp(PHONE, OTP, Duration.ofSeconds(300)));
        assertThat(thrown.getMessage()).isIn("gateway transport error", "gateway timed out");   // refused or unanswered: the platform decides
        assertThat(count("transport_error") + count("timeout")).isEqualTo(1);
        assertSilent(thrown);
    }

    @Test
    void the_properties_never_print_the_credential() {
        HttpOtpGatewayProperties p = new HttpOtpGatewayProperties();
        p.setAuthHeaderValue(SECRET);
        assertThat(p.toString()).doesNotContain("SUPER-SECRET");
    }
}
