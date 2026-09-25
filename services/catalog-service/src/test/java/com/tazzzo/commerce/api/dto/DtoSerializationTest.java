package com.tazzzo.commerce.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tazzzo.commerce.contract.ImageRole;
import com.tazzzo.commerce.contract.PublicErrorCode;
import com.tazzzo.commerce.contract.StockState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies JSON serialization exactly matches the frozen /v1 OpenAPI contract:
 * camelCase field names, int64 paise as JSON integers, optional fields omitted (not null),
 * flat ProductDetail (unwrapped card), and cursor omission behavior.
 */
class DtoSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ProductCardDto sampleCard() {
        return new ProductCardDto(
                "TZP-100002", "TZP-100002", "Aashirvaad Atta",
                "AASHIRVAAD", "Aashirvaad",
                "https://cdn.tazzzo.com/p/TZP-100002/thumb.webp",
                "5", "kg",
                26500L, 30000L, 11, 3500L, null,
                "TZG-000008", "TZV-000037", List.of("Bestseller"),
                null, null, false,
                StockState.IN_STOCK, null, 10, 1,
                true, 30, 45, true);
    }

    @Test void product_card_paise_are_integers_and_names_camelCase() throws Exception {
        JsonNode n = mapper.readTree(mapper.writeValueAsString(sampleCard()));
        assertEquals("TZP-100002", n.get("skuId").asText());
        assertTrue(n.get("sellingPricePaise").isIntegralNumber(), "paise must be JSON integer");
        assertEquals(26500L, n.get("sellingPricePaise").asLong());
        assertEquals(30000L, n.get("mrpPaise").asLong());
        assertEquals(3500L, n.get("discountAmountPaise").asLong());
        assertEquals("IN_STOCK", n.get("stockState").asText());
        assertTrue(n.get("buyable").asBoolean());
        assertEquals(1, n.get("minimumOrderQuantity").asInt());
        // optional null fields omitted, not serialized as null:
        assertFalse(n.has("offerSummary"));
        assertFalse(n.has("rating"));
        assertFalse(n.has("lowStockRemaining"));
        // no internal field ever leaks:
        assertFalse(n.has("fulfillmentLocationId"));
    }

    @Test void product_card_roundtrips() throws Exception {
        ProductCardDto c = sampleCard();
        assertEquals(c, mapper.readValue(mapper.writeValueAsString(c), ProductCardDto.class));
    }

    @Test void product_detail_is_flat_via_unwrapped_card() throws Exception {
        ProductDetailDto d = new ProductDetailDto(
                sampleCard(), "100% whole wheat", List.of("Chakki-fresh"),
                List.of(new ProductImageDto("https://cdn.tazzzo.com/p/1.webp", ImageRole.PRIMARY, 0, "front", null, null)),
                List.of(new ProductAttributeDto("weight", "Net Weight", "5 kg", "kg")),
                List.of(new ProductVariantDto("v-10kg", "TZP-100003", "10", "kg")),
                new LegalInformationDto("ITC Ltd", "India", "5 kg", "10012345", null),
                new ServiceabilityResponseDto(true, "SA-BLR-01", 7, 30, 45, "rq_1"),
                "REL-000123", "rq_1");
        JsonNode n = mapper.readTree(mapper.writeValueAsString(d));
        // card fields are FLAT at top level (not nested under "card"):
        assertFalse(n.has("card"));
        assertEquals("TZP-100002", n.get("skuId").asText());
        assertEquals("100% whole wheat", n.get("description").asText());
        assertEquals("PRIMARY", n.get("gallery").get(0).get("role").asText());
        assertEquals("India", n.get("legal").get("countryOfOrigin").asText());
        assertEquals("REL-000123", n.get("resolvedReleaseId").asText());
    }

    @Test void paged_response_omits_nextCursor_at_end() throws Exception {
        PagedProductResponse end = new PagedProductResponse(
                "REL-000123", new ServiceAreaSummaryDto("SA-BLR-01", true),
                List.of(sampleCard()), null, false, "rq_2");
        JsonNode n = mapper.readTree(mapper.writeValueAsString(end));
        assertFalse(n.has("nextCursor"), "nextCursor omitted when null");
        assertFalse(n.get("hasMore").asBoolean());
        assertTrue(n.get("serviceArea").get("serviceable").asBoolean());

        PagedProductResponse more = new PagedProductResponse(
                "REL-000123", null, List.of(sampleCard()), "eyJ2IjoxfQ", true, "rq_3");
        JsonNode n2 = mapper.readTree(mapper.writeValueAsString(more));
        assertEquals("eyJ2IjoxfQ", n2.get("nextCursor").asText());
        assertTrue(n2.get("hasMore").asBoolean());
        assertFalse(n2.has("serviceArea"));
    }

    @Test void error_envelope_shape() throws Exception {
        ErrorEnvelopeDto e = new ErrorEnvelopeDto(
                PublicErrorCode.RATE_LIMITED, "Too many requests", "rq_4", true, 2, null);
        JsonNode n = mapper.readTree(mapper.writeValueAsString(e));
        assertEquals("RATE_LIMITED", n.get("code").asText());
        assertTrue(n.get("retryable").asBoolean());
        assertEquals(2, n.get("retryAfterSeconds").asInt());
        assertFalse(n.has("details"));
    }

    @Test void serviceability_response_shape() throws Exception {
        JsonNode n = mapper.readTree(mapper.writeValueAsString(
                new ServiceabilityResponseDto(false, null, null, null, null, "rq_5")));
        assertFalse(n.get("serviceable").asBoolean());
        assertEquals("rq_5", n.get("requestId").asText());
        assertFalse(n.has("serviceAreaId"));
        assertFalse(n.has("etaMinutesMin"));
    }

    @Test void node_list_shape() throws Exception {
        JsonNode n = mapper.readTree(mapper.writeValueAsString(
                new NodeListResponse("REL-000123", List.of(new NodeDto("TZS-000001", "Staples")), "rq_6")));
        assertEquals("REL-000123", n.get("resolvedReleaseId").asText());
        assertEquals("TZS-000001", n.get("items").get(0).get("id").asText());
    }
}
