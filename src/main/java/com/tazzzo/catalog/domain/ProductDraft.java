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
        List<BundleComponent> bundleContents) { }
