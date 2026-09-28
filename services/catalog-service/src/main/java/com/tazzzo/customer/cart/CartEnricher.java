package com.tazzzo.customer.cart;

import com.tazzzo.commerce.contract.LocationQuery;
import com.tazzzo.commerce.contract.StockState;
import com.tazzzo.commerce.read.CommerceReadUnavailableException;
import com.tazzzo.commerce.read.CommerceSkuBatchReader;
import com.tazzzo.commerce.read.RuntimeProductCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PR-12C — turns persisted cart INTENT into a customer-useful response using CURRENT commerce
 * truth from the single authoritative seam ({@link CommerceSkuBatchReader}, which composes the
 * existing enricher — cart holds NO second buyable/stock algorithm).
 *
 * <p>Degradation: if commerce state cannot currently be read, lines are returned with the closed
 * issue {@code ENRICHMENT_UNAVAILABLE}, no price, {@code stockState=UNKNOWN}, {@code buyable=false}
 * — never a fabricated certainty. A known {@code OUT_OF_STOCK} and "cannot determine stock" stay
 * distinct. Item buyability is per REQUESTED quantity: {@code card.buyable && quantity <= maxOrder}.
 */
@Component
public class CartEnricher {

    private static final Logger log = LoggerFactory.getLogger(CartEnricher.class);

    private final CommerceSkuBatchReader commerce;

    public CartEnricher(CommerceSkuBatchReader commerce) {
        this.commerce = commerce;
    }

    public CartResponseDto present(CartState state, LocationQuery location, String requestId) {
        Map<String, RuntimeProductCard> cards = Map.of();
        boolean degraded = false;
        if (!state.lines().isEmpty()) {
            try {
                cards = commerce.readCurrent(state.lines().stream().map(CartState.Line::skuId).toList(), location);
            } catch (CommerceReadUnavailableException e) {
                log.warn("customer_cart_enrichment_unavailable category={}", e.category());
                degraded = true;
            }
        }
        List<CartResponseDto.Item> items = new ArrayList<>(state.lines().size());
        long subtotal = 0;
        int itemCount = 0;
        try {
            for (CartState.Line line : state.lines()) {
                CartResponseDto.Item item = item(line, cards.get(line.skuId()), degraded, location);
                items.add(item);
                if (item.lineTotalPaise() != null) {
                    subtotal = CartMath.add(subtotal, item.lineTotalPaise());
                }
                itemCount = Math.addExact(itemCount, line.quantity());
            }
        } catch (ArithmeticException e) {
            log.error("customer_cart_total_overflow");
            throw new CartFailure(CartFailure.Reason.UNAVAILABLE);
        }
        return new CartResponseDto(state.version(), items, itemCount, items.size(), subtotal,
                state.expiresAt() == null ? null : state.expiresAt().toString(), requestId);
    }

    private CartResponseDto.Item item(CartState.Line line, RuntimeProductCard card, boolean degraded,
                                      LocationQuery location) {
        List<CartIssue> issues = new ArrayList<>();
        String added = line.addedAt().toString();
        String updated = line.updatedAt().toString();
        if (degraded) {
            issues.add(CartIssue.ENRICHMENT_UNAVAILABLE);
            return new CartResponseDto.Item(line.skuId(), line.quantity(), added, updated, null, null,
                    new CartResponseDto.Availability(StockState.UNKNOWN.name(), 0, null), null, false, issues);
        }
        if (card == null) {
            issues.add(CartIssue.PRODUCT_UNAVAILABLE);
            return new CartResponseDto.Item(line.skuId(), line.quantity(), added, updated, null, null,
                    new CartResponseDto.Availability(StockState.UNKNOWN.name(), 0, null), null, false, issues);
        }
        Long unit = card.sellingPricePaise();
        if (unit == null) {
            issues.add(CartIssue.PRICE_UNAVAILABLE);
        }
        if (!location.isPresent()) {
            issues.add(CartIssue.LOCATION_REQUIRED);
        } else if (Boolean.FALSE.equals(card.serviceable())) {
            issues.add(CartIssue.UNSERVICEABLE);
        } else if (Boolean.TRUE.equals(card.serviceable())) {
            if (card.stockState() == StockState.OUT_OF_STOCK) {
                issues.add(CartIssue.OUT_OF_STOCK);
            } else if (card.stockState() == StockState.UNKNOWN) {
                issues.add(CartIssue.STOCK_UNKNOWN);
            } else if (line.quantity() > card.maxOrderQuantity()) {
                issues.add(CartIssue.INSUFFICIENT_STOCK);
            }
        }
        Long lineTotal = null;
        if (unit != null) {
            try {
                lineTotal = CartMath.lineTotal(unit, line.quantity());
            } catch (ArithmeticException e) {
                log.error("customer_cart_total_overflow");
                throw new CartFailure(CartFailure.Reason.UNAVAILABLE);
            }
        }
        boolean buyable = issues.isEmpty() && card.buyable() && line.quantity() <= card.maxOrderQuantity();
        return new CartResponseDto.Item(line.skuId(), line.quantity(), added, updated,
                new CartResponseDto.Product(card.title(), card.brandCode(), card.thumbnailUrl()),
                unit == null ? null : new CartResponseDto.Price(unit, card.mrpPaise(), "INR"),
                new CartResponseDto.Availability(card.stockState().name(), card.maxOrderQuantity(),
                        card.serviceable()),
                lineTotal, buyable, issues);
    }
}
