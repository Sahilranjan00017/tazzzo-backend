package com.tazzzo.media;

/**
 * Who a MediaSet belongs to (PR-05 ownership freeze). Explicit owner typing instead of
 * ambiguous nullable productId/skuId pairs: PRODUCT media is shared branding/product imagery;
 * SKU media is variant/pack-specific imagery. At launch skuId == productId, but the model never
 * assumes it — when variants diverge, both owner types coexist without schema change, and the
 * Commerce Read composer (which knows both ids) performs SKU→PRODUCT fallback (STEP 15 seam).
 */
public enum MediaOwnerType {
    PRODUCT,
    SKU
}
