package com.tazzzo.pricing.admin;

import com.tazzzo.catalog.api.AdminActors;
import com.tazzzo.catalog.tx.ProductQueryService;
import com.tazzzo.common.money.Currency;
import com.tazzzo.pricing.InvalidPriceException;
import com.tazzzo.pricing.Price;
import com.tazzzo.pricing.PriceLookup;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.pricing.UpsertPriceCommand;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * INTERNAL admin transport for the canonical current price of a SKU. Money is explicit paise (never rupees or floats).
 * {@code expectedVersion} absent = create, present = compare-and-set update; the audit actor is the AUTHENTICATED
 * principal ({@link AdminActors}), never a body field; the SKU must be an existing product. Every rule (validation,
 * sanity ceiling, MRP >= selling, CAS, ledger row + event in one transaction) lives in {@link PricingService}. Effective
 * windows are not offered: the service accepts only immediate prices.
 */
@RestController
@RequestMapping("/api/v1/admin/prices/{skuId}")
public class PriceAdminController {

    static final String SOURCE = "admin-api";

    record PriceRequest(Long sellingPricePaise, Long mrpPaise, String currency, Long expectedVersion) { }

    record PriceResponse(String skuId, String currency, long sellingPricePaise, long mrpPaise, long version, boolean active,
                         String status) { }

    private final PricingService pricing;
    private final ProductQueryService products;

    public PriceAdminController(PricingService pricing, ProductQueryService products) {
        this.pricing = pricing;
        this.products = products;
    }

    @GetMapping
    public PriceResponse get(@PathVariable("skuId") String skuId) {
        products.requireProduct(skuId);
        PriceLookup lookup = pricing.findCurrentPrice(skuId);
        Price p = lookup.price();
        if (p == null) {
            throw new PriceNotSetException();
        }
        return new PriceResponse(p.skuId(), p.currency().name(), p.sellingPricePaise(), p.mrpPaise(), p.version(), p.active(),
                lookup.status().name());
    }

    @PutMapping
    public ResponseEntity<PriceResponse> put(@PathVariable("skuId") String skuId, @RequestBody PriceRequest body,
                                             HttpServletRequest request) {
        if (body == null || body.sellingPricePaise() == null || body.mrpPaise() == null) {
            throw new InvalidPriceException("sellingPricePaise and mrpPaise are required");
        }
        products.requireProduct(skuId);
        Currency currency;
        try {
            currency = body.currency() == null ? Currency.INR : Currency.valueOf(body.currency());
        } catch (IllegalArgumentException e) {
            throw new InvalidPriceException("unsupported currency");
        }
        pricing.upsertPrice(new UpsertPriceCommand(skuId, body.sellingPricePaise(), body.mrpPaise(), currency, null, null,
                SOURCE, body.expectedVersion()), AdminActors.require(request));
        return ResponseEntity.status(body.expectedVersion() == null ? HttpStatus.CREATED : HttpStatus.OK).body(get(skuId));
    }

    /** No price exists yet for an existing product. */
    static final class PriceNotSetException extends RuntimeException {
        PriceNotSetException() {
            super("no price set");
        }
    }
}
