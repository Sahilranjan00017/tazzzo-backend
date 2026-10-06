package com.tazzzo.bulkimport;

import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.tx.ProductQueryService;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.inventory.InventoryService;
import com.tazzzo.pricing.PricingService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class BulkImportConfig {

    @Bean
    BulkImportService bulkImportService(PricingService pricing, InventoryService inventory, ProductQueryService products,
                                        MongoDatabase db, Tx tx, com.mongodb.client.MongoClient client,
                                        com.tazzzo.catalog.schema.AttributeGovernanceService governance,
                                        com.tazzzo.catalog.schema.CanonicalKeyService canonicalKeys,
                                        com.tazzzo.catalog.tx.MintService mint) {
        return new BulkImportService(pricing, inventory, products, new DomainAudit(db, Clock.systemUTC()), tx)
                .withProducts(new ProductImportValidator(db, client, governance, canonicalKeys), mint);
    }
}
