package com.tazzzo.customer.checkout;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * PR-13A — the public quote. A validated snapshot at {@code createdAt}: NOT a stock reservation and
 * NOT a final payable total (no delivery/platform fee, tax, tip or discount). Never exposes a
 * fulfillment/routing identity.
 *
 * <p>{@code benefitPreview} is the ADVISORY Benefits result stored with this quote (present on every quote created
 * since Benefits preview exists; ABSENT on an older quote, which is NOT the same as {@code applied=false}). It is
 * projected from the stored snapshot only; Order placement re-evaluates Benefits authoritatively and may differ.
 * {@code subtotalPaise} stays the canonical merchandise subtotal; there is no net/payable field.
 */
public record CheckoutQuoteDto(String quoteId, long cartVersion, String addressId, List<Item> items, int itemCount,
                               int distinctItemCount, long subtotalPaise, String currency, String createdAt,
                               String expiresAt, @JsonInclude(JsonInclude.Include.NON_NULL) BenefitPreview benefitPreview,
                               String requestId) {

    public record Item(String skuId, int quantity, long unitPricePaise, long lineTotalPaise) {
    }

    /** {@code applied=false}: no discount fields. {@code applied=true}: {@code discountPaise >= 1} and
     *  {@code discountBps} in 1..10000. Conditional presence, never null placeholders. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BenefitPreview(boolean applied, Long discountPaise, Integer discountBps) {
        public BenefitPreview {
            if (applied) {
                if (discountPaise == null || discountPaise < 1) {
                    throw new IllegalArgumentException("an applied benefit preview needs discountPaise >= 1");
                }
                if (discountBps == null || discountBps < 1 || discountBps > 10_000) {
                    throw new IllegalArgumentException("an applied benefit preview needs discountBps within 1..10000");
                }
            } else if (discountPaise != null || discountBps != null) {
                throw new IllegalArgumentException("a not-applied benefit preview carries no discount fields");
            }
        }

        static BenefitPreview of(CheckoutBenefitPreview p) {
            return switch (p) {
                case CheckoutBenefitPreview.NotApplied n -> new BenefitPreview(false, null, null);
                case CheckoutBenefitPreview.Applied a -> new BenefitPreview(true, a.discountPaise(), a.discountBps());
            };
        }
    }

    static CheckoutQuoteDto of(CheckoutQuote q, String requestId) {
        return new CheckoutQuoteDto(q.quoteId(), q.cartVersion(), q.addressId(),
                q.lines().stream().map(l -> new Item(l.skuId(), l.quantity(), l.unitPricePaise(), l.lineTotalPaise()))
                        .toList(),
                q.itemCount(), q.lines().size(), q.subtotalPaise(), q.currency(), q.createdAt().toString(),
                q.expiresAt().toString(), q.benefitPreview().map(BenefitPreview::of).orElse(null), requestId);
    }
}
