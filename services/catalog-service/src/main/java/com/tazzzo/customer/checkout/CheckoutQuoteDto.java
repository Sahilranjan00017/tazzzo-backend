package com.tazzzo.customer.checkout;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * PR-13A — the public quote. A validated snapshot at {@code createdAt}: NOT a stock reservation. Its optional
 * {@code moneyPreview} is the BINDING customer money of the quote (see below); no delivery/platform fee, tax or tip
 * exists in it. Never exposes a fulfillment/routing identity.
 *
 * <p>{@code benefitPreview} is the Benefits decision frozen with this quote and the basis of its money (present on
 * every quote created since Benefits preview exists; ABSENT on an older quote, which is NOT the same as
 * {@code applied=false}). It is projected from the stored snapshot only. {@code subtotalPaise} stays the canonical
 * merchandise subtotal.
 *
 * <p>{@code moneyPreview} is the BINDING customer money of this quote ({@code payablePaise =
 * merchandiseSubtotalPaise - benefitDiscountPaise}): the amount due if this exact quote is successfully ordered. Order
 * placement reproduces it exactly or is refused with {@code PAYABLE_CHANGED} (a fresh quote is then required); ABSENT on
 * a quote created before the money model, which is NOT a zero payable and cannot be ordered. {@code 0} is valid.
 */
public record CheckoutQuoteDto(String quoteId, long cartVersion, String addressId, List<Item> items, int itemCount,
                               int distinctItemCount, long subtotalPaise, String currency, String createdAt,
                               String expiresAt, @JsonInclude(JsonInclude.Include.NON_NULL) BenefitPreview benefitPreview,
                               @JsonInclude(JsonInclude.Include.NON_NULL) MoneyPreview moneyPreview,
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

    /** The three binding commerce amounts; always all present. No reason, identity, fee, tax or payment field. */
    public record MoneyPreview(long merchandiseSubtotalPaise, long benefitDiscountPaise, long payablePaise) {
        public MoneyPreview {
            if (merchandiseSubtotalPaise < 0 || benefitDiscountPaise < 0
                    || benefitDiscountPaise > merchandiseSubtotalPaise
                    || payablePaise != merchandiseSubtotalPaise - benefitDiscountPaise) {
                throw new IllegalArgumentException("inconsistent money preview");
            }
        }

        static MoneyPreview of(CheckoutMoneyPreview p) {
            return new MoneyPreview(p.merchandiseSubtotalPaise(), p.benefitDiscountPaise(), p.payablePaise());
        }
    }

    static CheckoutQuoteDto of(CheckoutQuote q, String requestId) {
        return new CheckoutQuoteDto(q.quoteId(), q.cartVersion(), q.addressId(),
                q.lines().stream().map(l -> new Item(l.skuId(), l.quantity(), l.unitPricePaise(), l.lineTotalPaise()))
                        .toList(),
                q.itemCount(), q.lines().size(), q.subtotalPaise(), q.currency(), q.createdAt().toString(),
                q.expiresAt().toString(), q.benefitPreview().map(BenefitPreview::of).orElse(null),
                q.moneyPreview().map(MoneyPreview::of).orElse(null), requestId);
    }
}
