package com.tazzzo.auth.otp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * A vendor-neutral HTTPS SMS-gateway adapter behind {@link OtpDeliveryProvider}: it POSTs
 * {@code {"to":"+91...","message":"...","sender":"..."}} to the configured gateway with the configured credential header
 * and treats any 2xx as "accepted for delivery". Many deployments front their SMS vendor with such an internal gateway;
 * a vendor-specific adapter (and its India DLT template registration) is a separate, external step.
 *
 * <p>Hard rules: redirects are NEVER followed (a hostile or misconfigured 3xx cannot bounce the code or the credential
 * elsewhere); the connect and read timeouts are bounded and fit inside the OTP delivery deadline; the OTP, the phone number,
 * the URL, the credential and the response body appear in no log line, exception message or metric -- failures surface as
 * {@link OtpProviderException} with a fixed, bounded reason; the only metric is {@code otp_gateway_send{outcome}} over a
 * closed set.
 */
public class HttpOtpDeliveryProvider implements OtpDeliveryProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpOtpDeliveryProvider.class);

    public enum Outcome { ACCEPTED, REJECTED, TIMEOUT, TRANSPORT_ERROR, REDIRECT }

    private final URI uri;
    private final HttpOtpGatewayProperties properties;
    private final HttpClient client;
    private final MeterRegistry registry;

    public HttpOtpDeliveryProvider(HttpOtpGatewayProperties properties, long deliveryTimeoutSeconds, MeterRegistry registry) {
        this.properties = Objects.requireNonNull(properties);
        this.uri = properties.validate(deliveryTimeoutSeconds);
        this.registry = registry;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public void sendLoginOtp(Phone phone, String otp, Duration expiresIn) {
        String message = properties.getMessageTemplate()
                .replace("{otp}", otp)
                .replace("{minutes}", Long.toString(Math.max(1, (expiresIn.getSeconds() + 59) / 60)));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(properties.getReadTimeoutMillis()))
                .header("Content-Type", "application/json")
                .header(properties.getAuthHeaderName(), properties.getAuthHeaderValue())
                .POST(HttpRequest.BodyPublishers.ofString(body(phone.value(), message), StandardCharsets.UTF_8))
                .build();
        HttpResponse<Void> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (java.net.http.HttpTimeoutException e) {
            fail(Outcome.TIMEOUT, "gateway timed out");
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(Outcome.TRANSPORT_ERROR, "gateway call interrupted");
            return;
        } catch (IOException e) {
            fail(Outcome.TRANSPORT_ERROR, "gateway transport error");
            return;
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            record(Outcome.ACCEPTED);
            return;
        }
        if (status >= 300 && status < 400) {
            fail(Outcome.REDIRECT, "gateway answered a redirect");
            return;
        }
        fail(Outcome.REJECTED, "gateway rejected the message");
    }

    private void fail(Outcome outcome, String reason) {
        record(outcome);
        // fixed text only: no phone, no code, no URL, no credential, no response body
        log.warn("otp_gateway_send_failed outcome={}", outcome);
        throw new OtpProviderException(reason);
    }

    private void record(Outcome outcome) {
        try {
            Counter.builder("otp_gateway_send").tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
                    .register(registry).increment();
        } catch (RuntimeException e) {
            log.warn("otp gateway metric recording failed and was ignored: {}", e.getClass().getSimpleName());
        }
    }

    /** Hand-built JSON so no serializer can ever be configured to log the payload; every value is escaped. */
    private String body(String to, String message) {
        StringBuilder sb = new StringBuilder("{\"to\":").append(quote(to)).append(",\"message\":").append(quote(message));
        if (properties.getSender() != null) {
            sb.append(",\"sender\":").append(quote(properties.getSender()));
        }
        return sb.append('}').toString();
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
