package com.tazzzo.commerce.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tazzzo.commerce.contract.PublicErrorCode;
import com.tazzzo.commerce.contract.StockState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * STEP 26 (PR-06) — CRITICAL DATA BOUNDARY REGRESSION.
 * fulfillmentLocationId is INTERNAL routing identity and must NEVER appear in any public
 * commerce DTO — neither as a declared record component nor as a serialized JSON key.
 *
 * <p>The declared-shape check AUTO-DISCOVERS every record in this package from the compiled
 * classes directory (dependency-free), so a future public DTO is covered the moment it exists —
 * no manual list to forget. A minimum-count assertion guards against the scan silently finding
 * nothing.
 */
class PublicDtoLocationLeakTest {

    private static final List<String> FORBIDDEN = List.of(
            "fulfillmentlocation", "fulfillment_location", "warehouseid", "storeid", "darkstore");

    /** Auto-discover all record classes in this package across every classpath copy of it. */
    private static List<Class<?>> discoverPublicDtos() throws Exception {
        String pkgPath = "com/tazzzo/commerce/api/dto";
        var urls = java.util.Collections.list(
                PublicDtoLocationLeakTest.class.getClassLoader().getResources(pkgPath));
        assertFalse(urls.isEmpty(), "package directory not found on classpath");
        java.util.Set<Class<?>> records = new java.util.LinkedHashSet<>();
        for (java.net.URL url : urls) {
            assertEquals("file", url.getProtocol(), "expected exploded classes dir under surefire");
            java.io.File dir = new java.io.File(url.toURI());
            String[] files = dir.list();
            if (files == null) continue;
            for (String f : files) {
                if (!f.endsWith(".class") || f.contains("$") || f.contains("Test")) continue;
                Class<?> c = Class.forName("com.tazzzo.commerce.api.dto." + f.substring(0, f.length() - 6));
                if (c.isRecord()) records.add(c);
            }
        }
        assertTrue(records.size() >= 12,
                "DTO auto-discovery degraded: found only " + records.size() + " records");
        return List.copyOf(records);
    }

    @Test void no_public_dto_declares_internal_location_identity() throws Exception {
        for (Class<?> dto : discoverPublicDtos()) {
            for (RecordComponent c : dto.getRecordComponents()) {
                String name = c.getName().toLowerCase(Locale.ROOT);
                for (String bad : FORBIDDEN) {
                    assertFalse(name.contains(bad),
                            dto.getSimpleName() + "." + c.getName() + " leaks internal location identity");
                }
            }
        }
    }

    @Test void serialized_public_payloads_carry_no_internal_location_keys() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ProductCardDto card = new ProductCardDto("TZP-1", "TZP-1", "X", null, null, null, null, null,
                100L, 200L, null, null, null, null, null, null, null, null, false,
                StockState.IN_STOCK, null, 5, 1, true, 30, 45, true);
        Object[] samples = {
                card,
                new ProductDetailDto(card, null, null, List.of(), List.of(), List.of(), null,
                        new ServiceabilityResponseDto(true, "SA-BLR-01", 1, 30, 45, "rq"),
                        "REL-1", "rq"),
                new PagedProductResponse("REL-1", new ServiceAreaSummaryDto("SA-BLR-01", true),
                        List.of(card), null, false, "rq"),
                new ServiceabilityResponseDto(true, "SA-BLR-01", 1, 30, 45, "rq"),
                new ServiceAreaSummaryDto("SA-BLR-01", true),
                new ErrorEnvelopeDto(PublicErrorCode.NOT_FOUND, "x", "rq", false, null, null)};
        for (Object sample : samples) {
            String json = mapper.writeValueAsString(sample).toLowerCase(Locale.ROOT);
            for (String bad : FORBIDDEN) {
                assertFalse(json.contains(bad),
                        sample.getClass().getSimpleName() + " serialization leaks: " + bad);
            }
        }
    }
}
