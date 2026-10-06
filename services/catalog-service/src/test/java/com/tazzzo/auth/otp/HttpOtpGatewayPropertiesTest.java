package com.tazzzo.auth.otp;

import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The gateway configuration is validated fail-closed, before the first customer request. */
class HttpOtpGatewayPropertiesTest {

    private static HttpOtpGatewayProperties valid() {
        HttpOtpGatewayProperties p = new HttpOtpGatewayProperties();
        p.setUrl("https://sms-gateway.internal.example/send");
        p.setAuthHeaderValue("Bearer x");
        return p;
    }

    private static void refused(String why, Consumer<HttpOtpGatewayProperties> mutate) {
        HttpOtpGatewayProperties p = valid();
        mutate.accept(p);
        assertThatThrownBy(() -> p.validate(60)).as(why).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_complete_https_configuration_is_accepted_and_loopback_may_use_http() {
        assertThat(valid().validate(60).getHost()).isEqualTo("sms-gateway.internal.example");
        HttpOtpGatewayProperties local = valid();
        local.setUrl("http://127.0.0.1:9000/send");
        assertThat(local.validate(60).getPort()).isEqualTo(9000);
        local.setUrl("http://localhost/send");
        local.validate(60);
    }

    @Test
    void every_unsafe_or_incomplete_setting_is_refused() {
        refused("no url", p -> p.setUrl(null));
        refused("blank url", p -> p.setUrl("  "));
        refused("plain http to a remote host", p -> p.setUrl("http://sms.example/send"));
        refused("ftp", p -> p.setUrl("ftp://sms.example/send"));
        refused("no scheme", p -> p.setUrl("sms.example/send"));
        refused("credentials in the url", p -> p.setUrl("https://user:pw@sms.example/send"));
        refused("not a uri", p -> p.setUrl("https://exa mple/"));
        refused("no credential", p -> p.setAuthHeaderValue(null));
        refused("blank credential", p -> p.setAuthHeaderValue(" "));
        refused("control chars in the credential", p -> p.setAuthHeaderValue("a\r\nX-Evil: 1"));
        refused("bad header name", p -> p.setAuthHeaderName("Bad Header"));
        refused("template without the code", p -> p.setMessageTemplate("Your code is ready"));
        refused("template too long", p -> p.setMessageTemplate("{otp} " + "x".repeat(400)));
        refused("sender with symbols", p -> p.setSender("<script>"));
        refused("timeouts too small", p -> p.setReadTimeoutMillis(10));
        refused("timeouts exceed the delivery deadline", p -> { p.setConnectTimeoutMillis(30_000); p.setReadTimeoutMillis(30_000); });
    }

    @Test
    void the_error_messages_never_contain_the_credential() {
        HttpOtpGatewayProperties p = valid();
        p.setAuthHeaderValue("Bearer TOP-SECRET\r\n");
        assertThatThrownBy(() -> p.validate(60)).hasMessageNotContaining("TOP-SECRET");
        p.setUrl("https://user:TOP-SECRET@sms.example/send");
        assertThatThrownBy(() -> p.validate(60)).hasMessageNotContaining("TOP-SECRET");
    }
}
