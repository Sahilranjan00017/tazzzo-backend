package com.tazzzo.commerce.read;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Default {@link ProductCardBaseReadPort} over {@code product_card_base}. */
public class ProductCardBaseReader implements ProductCardBaseReadPort {

    private final MongoDatabase db;

    public ProductCardBaseReader(MongoDatabase db) {
        this.db = Objects.requireNonNull(db);
    }

    @Override
    public Optional<ProductCardBaseProjection> findBySku(String skuId) {
        Document d = db.getCollection(ProductCardProjectionService.COLLECTION)
                .find(Filters.eq("sku_id", skuId)).first();
        return d == null ? Optional.empty()
                : Optional.of(ProductCardProjectionService.fromDocument(d));
    }

    @Override
    public List<ProductCardBaseProjection> findBySkuIds(Collection<String> skuIds) {
        LinkedHashSet<String> distinct = new LinkedHashSet<>(skuIds);
        if (distinct.isEmpty()) {
            return List.of();
        }
        Map<String, ProductCardBaseProjection> bySku = new LinkedHashMap<>();
        for (Document d : db.getCollection(ProductCardProjectionService.COLLECTION)
                .find(Filters.in("sku_id", distinct))) {
            ProductCardBaseProjection p = ProductCardProjectionService.fromDocument(d);
            bySku.put(p.skuId(), p);
        }
        // preserve the caller's requested order; missing SKUs are simply absent
        List<ProductCardBaseProjection> out = new ArrayList<>();
        for (String id : distinct) {
            ProductCardBaseProjection p = bySku.get(id);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }
}
