package com.tazzzo.bulkimport;

import com.tazzzo.catalog.ProductIdCorpus;
import com.tazzzo.catalog.api.ApiDtos.CreateProductRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The import's shape check reports a bad id as an explicit row error (the Mongo validator probe stays a backstop). */
class ProductImportIdShapeTest {

    static CreateProductRequest row(String id) {
        return new CreateProductRequest(id, "single", "internal", "k|x", null, "BR", "T", "TZV-000001", "R1",
                "provisional", Map.of(), List.of(), null, null);
    }

    @Test
    void valid_ids_pass_and_invalid_ids_are_explicit_row_errors() {
        for (String id : ProductIdCorpus.VALID) assertThat(ProductImportValidator.shape(row(id))).as(id).isNull();
        for (String id : ProductIdCorpus.INVALID) {
            assertThat(ProductImportValidator.shape(row(id))).as(id).startsWith("id must match ");
        }
    }
}
