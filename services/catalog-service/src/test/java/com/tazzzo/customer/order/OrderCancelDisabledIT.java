package com.tazzzo.customer.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.CatalogApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Default deployment: no cancellation window configured, so customer cancellation is closed (a business decision, not invented). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractOrderSlotIT.BigAddressLimit.class})
class OrderCancelDisabledIT extends AbstractOrderSlotIT {

    @Test
    void with_no_window_configured_a_customer_cannot_cancel_and_nothing_changes() {
        Shopper s = shopper();
        String orderId = place(s.token(), body(s, null)).getBody().get("orderId").asText();
        ResponseEntity<JsonNode> r = call(HttpMethod.POST, "/v1/customer/orders/" + orderId + "/cancel", s.token(), null, Map.of("reason", "OTHER"));
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(r.getBody().get("code").asText()).isEqualTo("CANCELLATION_WINDOW_CLOSED");
        assertThat(onHand(s.sku())).isEqualTo(8);
    }
}
