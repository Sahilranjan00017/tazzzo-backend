package com.tazzzo.commerce.contract;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Freezes the exact frozen enum value sets — guards against speculative additions. */
class EnumContractTest {

    @Test void stock_state_values() {
        assertEquals(List.of("IN_STOCK", "LOW_STOCK", "OUT_OF_STOCK", "UNKNOWN"),
                names(StockState.values()));
    }

    @Test void image_role_values() {
        assertEquals(List.of("PRIMARY", "GALLERY"), names(ImageRole.values()));
    }

    @Test void public_error_code_values() {
        assertEquals(List.of("INVALID_REQUEST", "INVALID_CURSOR", "NOT_FOUND",
                "RATE_LIMITED", "SERVICE_UNAVAILABLE", "INTERNAL"), names(PublicErrorCode.values()));
    }

    private static List<String> names(Enum<?>[] values) {
        return java.util.Arrays.stream(values).map(Enum::name).toList();
    }
}
