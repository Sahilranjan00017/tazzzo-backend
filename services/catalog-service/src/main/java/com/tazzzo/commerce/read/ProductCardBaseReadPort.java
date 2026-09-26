package com.tazzzo.commerce.read;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Smallest internal read boundary over persisted {@code product_card_base} rows (PR-08,
 * STEP 25). Returns domain projections, never Mongo documents. Corrupt rows fail loudly via
 * {@code fromDocument} (fail-fast, PR-07). No controller; the NOT-LIVE freshness gate on the
 * projection applies unchanged.
 */
public interface ProductCardBaseReadPort {

    Optional<ProductCardBaseProjection> findBySku(String skuId);

    /** Missing SKUs are simply absent; INPUT ORDER of found rows is preserved. */
    List<ProductCardBaseProjection> findBySkuIds(Collection<String> skuIds);
}
