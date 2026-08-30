package com.tazzzo.catalog.domain;

import java.util.List;
import java.util.Map;

public record ProductDraft(
        String id,
        String productType,
        String identityType,
        String internalKey,
        List<GtinBinding> gtins,
        String brandCode,
        String title,
        String verticalId,
        String releaseId,
        String classificationStatus,
        Map<String, Object> attributes,
        List<String> evidenceRefs,
        List<BundleComponent> bundleContents,
        PackOf packOf) {

    /**
     * F-5: pre-existing 13-arg form retained so every current call site keeps compiling.
     * Only variant_pack drafts carry a packOf.
     */
    public ProductDraft(String id, String productType, String identityType, String internalKey,
                        List<GtinBinding> gtins, String brandCode, String title, String verticalId,
                        String releaseId, String classificationStatus,
                        Map<String, Object> attributes, List<String> evidenceRefs,
                        List<BundleComponent> bundleContents) {
        this(id, productType, identityType, internalKey, gtins, brandCode, title, verticalId,
                releaseId, classificationStatus, attributes, evidenceRefs, bundleContents, null);
    }
}
