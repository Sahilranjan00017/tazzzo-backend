package com.tazzzo.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** With no origin configured (the default), no CORS grant is ever issued: a browser cross-origin call is refused by the browser. */
class CorsDisabledByDefaultIT extends AbstractApiIT {

    @Test
    void no_origin_ever_receives_an_allow_origin_header() {
        HttpHeaders h = headers(null);
        h.setOrigin("https://cms.example.test");
        h.setAccessControlRequestMethod(HttpMethod.GET);
        ResponseEntity<String> preflight = rest.exchange(url("/health/live"), HttpMethod.OPTIONS, new HttpEntity<>(h), String.class);
        assertThat(preflight.getHeaders().getAccessControlAllowOrigin()).isNull();
        HttpHeaders simple = headers(null);
        simple.setOrigin("https://cms.example.test");
        ResponseEntity<String> res = rest.exchange(url("/health/live"), HttpMethod.GET, new HttpEntity<>(simple), String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getAccessControlAllowOrigin()).isNull();
    }
}
