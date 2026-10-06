package com.tazzzo.auth.otp;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Settings of the generic HTTPS SMS-gateway adapter ({@code provider-mode=HTTP}). Bound as the {@code http} group of
 * {@code tazzzo.customer-auth.otp}. The credential ({@code authHeaderValue}) is supplied by the deployment's secret store
 * (an environment variable), never committed, and never appears in {@link #toString()}, logs or exceptions.
 */
public class HttpOtpGatewayProperties {

    private String url;
    private String authHeaderName = "Authorization";
    private String authHeaderValue;
    private String sender;
    private String messageTemplate = "{otp} is your Tazzzo verification code. It expires in {minutes} minutes. Do not share it with anyone.";
    private long connectTimeoutMillis = 2_000;
    private long readTimeoutMillis = 3_000;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getAuthHeaderName() { return authHeaderName; }
    public void setAuthHeaderName(String authHeaderName) { this.authHeaderName = authHeaderName; }
    public String getAuthHeaderValue() { return authHeaderValue; }
    public void setAuthHeaderValue(String authHeaderValue) { this.authHeaderValue = authHeaderValue; }
    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }
    public String getMessageTemplate() { return messageTemplate; }
    public void setMessageTemplate(String messageTemplate) { this.messageTemplate = messageTemplate; }
    public long getConnectTimeoutMillis() { return connectTimeoutMillis; }
    public void setConnectTimeoutMillis(long connectTimeoutMillis) { this.connectTimeoutMillis = connectTimeoutMillis; }
    public long getReadTimeoutMillis() { return readTimeoutMillis; }
    public void setReadTimeoutMillis(long readTimeoutMillis) { this.readTimeoutMillis = readTimeoutMillis; }

    /**
     * Fail-closed validation, run when the adapter is wired: HTTPS only (plain HTTP is allowed solely for a loopback host,
     * which is how the tests and a local sidecar run), a credential present, a template that actually carries the code,
     * and timeouts that fit inside the OTP delivery deadline.
     *
     * @throws IllegalStateException the first violated rule, never including the credential
     */
    public URI validate(long deliveryTimeoutSeconds) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("tazzzo.customer-auth.otp.http.url is required for provider-mode HTTP");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("tazzzo.customer-auth.otp.http.url is not a valid URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null) {
            throw new IllegalStateException("tazzzo.customer-auth.otp.http.url must name a host and carry no credentials");
        }
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
        if (!scheme.equals("https") && !(scheme.equals("http") && loopback)) {
            throw new IllegalStateException("tazzzo.customer-auth.otp.http.url must be https (http only for a loopback host)");
        }
        if (authHeaderValue == null || authHeaderValue.isBlank()) {
            throw new IllegalStateException("tazzzo.customer-auth.otp.http.auth-header-value is required for provider-mode HTTP");
        }
        if (authHeaderName == null || !authHeaderName.matches("[A-Za-z][A-Za-z0-9-]{0,63}")) {
            throw new IllegalStateException("tazzzo.customer-auth.otp.http.auth-header-name is not a valid header name");
        }
        if (authHeaderValue.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
            throw new IllegalStateException("tazzzo.customer-auth.otp.http.auth-header-value contains control characters");
        }
        if (messageTemplate == null || !messageTemplate.contains("{otp}") || messageTemplate.length() > 320) {
            throw new IllegalStateException("tazzzo.customer-auth.otp.http.message-template must contain {otp} and be at most 320 chars");
        }
        if (sender != null && !sender.matches("[A-Za-z0-9 _-]{1,32}")) {
            throw new IllegalStateException("tazzzo.customer-auth.otp.http.sender must be 1..32 of letters, digits, space, _ or -");
        }
        if (connectTimeoutMillis < 100 || readTimeoutMillis < 100
                || connectTimeoutMillis + readTimeoutMillis >= deliveryTimeoutSeconds * 1000) {
            throw new IllegalStateException("tazzzo.customer-auth.otp.http timeouts must be >= 100 ms and total less than delivery-timeout-seconds");
        }
        return uri;
    }

    @Override
    public String toString() {
        return "HttpOtpGatewayProperties[credential=<redacted>]";
    }
}
