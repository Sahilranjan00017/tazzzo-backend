package com.tazzzo.catalog;

import com.tazzzo.catalog.domain.BundleComponent;
import com.tazzzo.catalog.domain.GtinBinding;
import com.tazzzo.catalog.domain.ProductDraft;

import java.util.List;
import java.util.Map;

public final class TestFixtures {

    private TestFixtures() { }

    public static ProductDraft single(String id, String gtin) {
        return new ProductDraft(id, "single", "gtin", null,
                List.of(new GtinBinding(gtin, "IN")), "BR-TEST", "Fixture " + id,
                "TZV-000123", "1.0.0", "provisional",
                Map.of("pack_size", 5, "pack_unit", "kg"), List.of("EV-000001"), null);
    }

    public static ProductDraft internalSingle(String id, String key) {
        return new ProductDraft(id, "single", "internal", key, null, "BR-TEST", "Fixture " + id,
                "TZV-000123", "1.0.0", "provisional", Map.of(), List.of(), null);
    }

    public static ProductDraft bundle(String id, List<BundleComponent> components) {
        return new ProductDraft(id, "bundle", "internal", null, null, "BR-TEST", "Bundle " + id,
                null, "1.0.0", "review", Map.of(), List.of(), components);
    }
}
