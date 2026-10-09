package com.tazzzo.catalog;

import com.tazzzo.catalog.api.ApiDtos.BundleComponentDto;
import com.tazzzo.catalog.api.ApiDtos.CreateProductRequest;
import com.tazzzo.catalog.api.ApiDtos.PackOfDto;
import com.tazzzo.catalog.api.ProductController;
import com.tazzzo.catalog.domain.ProductIds;
import com.tazzzo.content.ContentBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** One grammar, one corpus: the validator, create mapping and content blocks accept and reject exactly the same ids. */
class ProductIdGrammarTest {

    static CreateProductRequest create(String id, List<BundleComponentDto> bundle, PackOfDto pack) {
        return new CreateProductRequest(id, "single", "internal", "k|" + id, null, "BR", "T", "TZV-000001", "R1",
                "provisional", Map.of(), List.of(), bundle, pack);
    }

    static boolean railAccepts(String id) {
        try {
            ContentBlock.validate(ContentBlock.Type.PRODUCT_RAIL, "Rail", 0, null, null,
                    new ContentBlock.Payload(null, null, List.of(id), null, null, null, null, null, null));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static boolean bannerLinkAccepts(String id) {
        try {
            ContentBlock.validate(ContentBlock.Type.BANNER, "Banner", 0, null, null,
                    new ContentBlock.Payload("cms/home/a.webp", "product:" + id, List.of(), null, null, null, null, null, null));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Test
    void the_validator_accepts_exactly_the_valid_corpus() {
        for (String id : ProductIdCorpus.VALID) assertThat(ProductIds.isValid(id)).as(id).isTrue();
        for (String id : ProductIdCorpus.INVALID) assertThat(ProductIds.isValid(id)).as(id).isFalse();
        assertThat(ProductIds.isValid(null)).isFalse();
    }

    @Test
    void require_throws_a_clear_message_that_does_not_echo_the_input() {
        assertThatThrownBy(() -> ProductIds.require("TZP-a_b", "id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("id must match " + ProductIds.REGEX);
        assertThat(ProductIds.require("TZP-Med-3", "id")).isEqualTo("TZP-Med-3");   // returned unchanged: no case folding
    }

    @Test
    void the_create_mapping_gates_the_id_and_every_component_id() {
        for (String id : ProductIdCorpus.VALID) {
            assertThatCode(() -> ProductController.toDraft(create(id, null, null))).as(id).doesNotThrowAnyException();
            assertThatCode(() -> ProductController.toDraft(create("TZP-OK", List.of(new BundleComponentDto(id, 1, null)), null)))
                    .as("bundle " + id).doesNotThrowAnyException();
            assertThatCode(() -> ProductController.toDraft(create("TZP-OK", null, new PackOfDto(id, 2))))
                    .as("pack " + id).doesNotThrowAnyException();
        }
        for (String id : ProductIdCorpus.INVALID) {
            assertThatThrownBy(() -> ProductController.toDraft(create(id, null, null)))
                    .as(id).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ProductController.toDraft(create("TZP-OK", List.of(new BundleComponentDto(id, 1, null)), null)))
                    .as("bundle " + id).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ProductController.toDraft(create("TZP-OK", null, new PackOfDto(id, 2))))
                    .as("pack " + id).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ProductController.toDraft(create(null, null, null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void content_blocks_accept_and_reject_the_same_ids() {
        for (String id : ProductIdCorpus.VALID) {
            assertThat(railAccepts(id)).as("rail " + id).isTrue();
            assertThat(bannerLinkAccepts(id)).as("link " + id).isTrue();
        }
        for (String id : ProductIdCorpus.INVALID) {
            assertThat(railAccepts(id)).as("rail " + id).isFalse();
            assertThat(bannerLinkAccepts(id)).as("link " + id).isFalse();
        }
    }
}
