package com.tazzzo.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No endpoint of this service accepts multipart (media is uploaded straight to object storage; imports are JSON), so
 * multipart parsing is switched off ({@code spring.servlet.multipart.enabled=false}). Without a multipart resolver and
 * without a multipart config on the dispatcher servlet, Tomcat never parses parts: the multipart denial-of-service
 * class (CVE-2025-48988, CVE-2025-52520, CVE-2025-61795) has no entry point, and a multipart request is refused by
 * content negotiation like any other unsupported media type.
 */
class MultipartDisabledIT extends AbstractApiIT {

    @Autowired ApplicationContext context;

    @Test
    void no_multipart_resolver_and_no_multipart_config_on_the_dispatcher() {
        assertThat(context.getBeansOfType(MultipartResolver.class)).isEmpty();
        context.getBeansOfType(ServletRegistrationBean.class).values()
                .forEach(r -> assertThat(r.getMultipartConfig()).as(r.getServletName()).isNull());
    }

    @Test
    void a_multipart_request_is_refused_without_being_parsed() {
        HttpHeaders h = headers(CMS_TOKEN);
        h.set("Content-Type", "multipart/form-data; boundary=----tazzzo");
        // deliberately not a valid multipart body: a parser would fail on it; content negotiation refuses it first
        ResponseEntity<String> res = rest.exchange(url("/api/v1/products"), HttpMethod.POST,
                new HttpEntity<>("------tazzzo\r\nContent-Disposition: form-data; name=\"id\"\r\n\r\n", h), String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(415);
        assertThat(res.getHeaders().getFirst("X-Request-Id")).startsWith("req_");
    }
}
