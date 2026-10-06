package com.tazzzo.catalog.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public consumer envelopes keep the exact field order consumers have always received. Jackson 2.18 began ordering
 * record components by declaration, which reorders these three records (and no other record in the codebase: every
 * record's serialized property order was compared between Jackson 2.17.2 and 2.18.11). JSON objects are unordered, but a
 * dependency upgrade must not change public response bytes, so the order is pinned with {@code @JsonPropertyOrder}.
 */
class ConsumerWireOrderTest {

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    private List<String> fieldOrder(Object value) throws Exception {
        JsonNode node = mapper.readTree(mapper.writeValueAsString(value));
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void the_node_list_envelope_keeps_items_first() throws Exception {
        assertThat(fieldOrder(new ConsumerDtos.NodeListResponse("REL-1", List.of())))
                .containsExactly("items", "resolved_release_id");
    }

    @Test
    void the_product_list_envelope_keeps_items_first() throws Exception {
        assertThat(fieldOrder(new ConsumerDtos.ProductListResponse("REL-1", List.of(), "CURSOR")))
                .containsExactly("items", "resolved_release_id", "next_cursor");
    }

    @Test
    void the_product_detail_envelope_keeps_item_first() throws Exception {
        assertThat(fieldOrder(new ConsumerDtos.ProductDetailResponse("REL-1", null)))
                .containsExactly("item", "resolved_release_id");
    }
}
