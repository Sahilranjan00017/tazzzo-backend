package com.tazzzo.bulkimport.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.bulkimport.ProductImportValidator;
import com.tazzzo.catalog.schema.AttributeGovernanceService;
import com.tazzzo.catalog.schema.CanonicalKeyService;
import com.tazzzo.catalog.tx.MintService;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.audit.DomainAudit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class ImportJobConfig {

    @Bean
    ImportJobRepository importJobRepository(MongoDatabase db) {
        return new ImportJobRepository(db, Clock.systemUTC());
    }

    @Bean
    ImportJobService importJobService(ImportJobRepository repo, MongoDatabase db, Tx tx, ObjectMapper mapper,
                                      @Value("${tazzzo.imports.max-rows-per-job:250000}") long maxRowsPerJob,
                                      @Value("${tazzzo.imports.max-active-jobs:10}") int maxActiveJobs) {
        return new ImportJobService(repo, new DomainAudit(db, Clock.systemUTC()), tx, mapper, maxRowsPerJob, maxActiveJobs);
    }

    @Bean
    ImportJobWorker importJobWorker(ImportJobRepository repo, ImportJobService service, MongoDatabase db, MongoClient client,
                                    AttributeGovernanceService governance, CanonicalKeyService canonicalKeys, MintService mint,
                                    @Value("${tazzzo.scheduler.import-jobs-batch-size:500}") int batchSize,
                                    @Value("${tazzzo.scheduler.import-jobs-lease-ms:120000}") long leaseMs,
                                    @Value("${tazzzo.scheduler.import-jobs-tick-budget-ms:30000}") long tickBudgetMs) {
        return new ImportJobWorker(repo, service, new ProductImportValidator(db, client, governance, canonicalKeys), mint,
                Clock.systemUTC(), batchSize, leaseMs, tickBudgetMs);
    }
}
