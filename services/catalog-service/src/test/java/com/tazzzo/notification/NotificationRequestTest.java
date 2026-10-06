package com.tazzzo.notification;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationRequestTest {

    static NotificationRequest req(String customer, String subject, Map<String, String> params) {
        return new NotificationRequest(NotificationType.ORDER_CONFIRMED, customer, subject, params);
    }

    @Test
    void the_dedupe_key_is_type_and_subject() {
        assertThat(req("CUS_a1", "ORD_1", null).dedupeKey()).isEqualTo("ORDER_CONFIRMED:ORD_1");
        assertThat(req("CUS_a1", "ORD_1", null).params()).isEmpty();
    }

    @Test
    void ids_and_params_are_bounded_and_plain() {
        for (String bad : new String[]{null, "", "a b", "x".repeat(65), "+919800000000@x", "{\"$ne\":1}"}) {
            assertThatThrownBy(() -> req(bad, "ORD_1", null)).as(String.valueOf(bad)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> req("CUS_a1", bad, null)).as(String.valueOf(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new NotificationRequest(null, "CUS_a1", "ORD_1", null)).isInstanceOf(IllegalArgumentException.class);
        for (Map<String, String> bad : java.util.List.<Map<String, String>>of(Map.of("Total", "1"), Map.of("total", ""),
                Map.of("total", "x".repeat(65)), Map.of("total", "a\nb"), Map.of("1x", "1"))) {
            assertThatThrownBy(() -> req("CUS_a1", "ORD_1", bad)).as(bad.toString()).isInstanceOf(IllegalArgumentException.class);
        }
        Map<String, String> nine = new HashMap<>();
        for (int i = 0; i < 9; i++) nine.put("k" + i, "v");
        assertThatThrownBy(() -> req("CUS_a1", "ORD_1", nine)).isInstanceOf(IllegalArgumentException.class);
        nine.remove("k8");
        assertThat(req("CUS_a1", "ORD_1", nine).params()).hasSize(8);
        assertThat(req("CUS_a1", "ORD_1", Map.of("payable_paise", "x".repeat(64))).params()).hasSize(1);
    }
}
