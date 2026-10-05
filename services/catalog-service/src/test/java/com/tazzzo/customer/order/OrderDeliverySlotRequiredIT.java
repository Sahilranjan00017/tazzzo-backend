package com.tazzzo.customer.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.CatalogApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** With {@code tazzzo.checkout.delivery-slot-required=true} an order without a slot is refused, and nothing is written. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractOrderSlotIT.BigAddressLimit.class},
        properties = "tazzzo.checkout.delivery-slot-required=true")
class OrderDeliverySlotRequiredIT extends AbstractOrderSlotIT {

    @Test
    void a_slotless_order_is_400_and_a_slotted_one_succeeds() {
        Shopper s = shopper();
        openWindow(s.areaId(), "early", 5);
        ResponseEntity<JsonNode> refused = place(s.token(), body(s, null));
        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(db.getCollection("orders").countDocuments(new org.bson.Document("customerId", s.customerId()))).isZero();
        assertThat(place(s.token(), body(s, "early~" + tomorrow())).getStatusCode().value()).isEqualTo(200);
    }
}
