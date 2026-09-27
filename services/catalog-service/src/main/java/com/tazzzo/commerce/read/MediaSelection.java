package com.tazzzo.commerce.read;

import com.tazzzo.media.MediaLookup;
import com.tazzzo.media.MediaOwnerType;
import com.tazzzo.media.MediaReadPort;
import com.tazzzo.media.MediaSet;

import java.util.Optional;

/**
 * The ONE owner-fallback rule for choosing a media set (frozen in the PR-07 review, STEP 9),
 * shared by the card projection builder and the PDP composer so the policy can never fork:
 * <ul>
 *   <li>SKU media {@code MISSING} → fall back to PRODUCT media (nothing was ever authored for
 *       the SKU).</li>
 *   <li>SKU media {@code INACTIVE} → EXPLICIT SUPPRESSION, no fallback: an operator deliberately
 *       switched that SKU's imagery off, and silently substituting product-level imagery would
 *       undo that decision (and risk showing a wrong pack image).</li>
 *   <li>Neither present → empty (a valid item with no imagery; placeholder is a UI concern).</li>
 * </ul>
 */
final class MediaSelection {

    private MediaSelection() { }

    static Optional<MediaSet> selectFor(MediaReadPort media, String skuId, String productId) {
        MediaLookup sku = media.findMedia(MediaOwnerType.SKU, skuId);
        if (sku.status() == MediaLookup.Status.INACTIVE) {
            return Optional.empty(); // suppressed by explicit operator decision
        }
        MediaLookup chosen = sku.isPresent() ? sku
                : media.findMedia(MediaOwnerType.PRODUCT, productId);
        return chosen.presentSet();
    }
}
