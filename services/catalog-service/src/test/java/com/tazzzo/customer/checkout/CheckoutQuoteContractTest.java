package com.tazzzo.customer.checkout;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DTO <-> OpenAPI parity for the Checkout quote (the CI swagger validation only checks structure, not that the DTO and
 * the documented schema agree). Narrow on purpose: the property sets, the additive/conditional benefit preview and the
 * absence of anything internal.
 */
class CheckoutQuoteContractTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(String name) throws IOException {
        Path file = Path.of("../../docs/api/v1/openapi.yaml");
        assertThat(Files.exists(file)).as("openapi.yaml reachable from the module directory").isTrue();
        try (FileInputStream in = new FileInputStream(file.toFile())) {
            Map<String, Object> doc = new Yaml().load(in);
            Map<String, Object> components = (Map<String, Object>) doc.get("components");
            return (Map<String, Object>) ((Map<String, Object>) components.get("schemas")).get(name);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    private static Set<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(c -> c.getName()).collect(Collectors.toSet());
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_openapi_quote_properties_match_the_dto_components_and_benefit_preview_is_optional() throws IOException {
        Map<String, Object> quote = schema("CustomerCheckoutQuote");

        assertThat(properties(quote).keySet()).isEqualTo(components(CheckoutQuoteDto.class));
        List<String> required = (List<String>) quote.get("required");
        assertThat(required).as("additive: a legacy quote omits it").doesNotContain("benefitPreview", "moneyPreview");
        assertThat(required).contains("quoteId", "subtotalPaise", "requestId");
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_documented_benefit_preview_is_the_two_closed_conditional_shapes_with_no_internal_field() throws IOException {
        Map<String, Object> preview = (Map<String, Object>) properties(schema("CustomerCheckoutQuote"))
                .get("benefitPreview");
        List<Map<String, Object>> shapes = (List<Map<String, Object>>) preview.get("oneOf");

        assertThat(shapes).hasSize(2);
        Map<String, Object> notApplied = shapes.get(0);
        Map<String, Object> applied = shapes.get(1);
        assertThat(notApplied.get("additionalProperties")).isEqualTo(false);
        assertThat(applied.get("additionalProperties")).isEqualTo(false);
        assertThat((List<String>) notApplied.get("required")).containsExactly("applied")
                .doesNotContain("discountPaise", "discountBps");
        assertThat(properties(notApplied).keySet()).containsExactly("applied");
        assertThat((List<String>) applied.get("required")).containsExactly("applied", "discountPaise", "discountBps");
        assertThat(properties(applied).keySet()).containsExactly("applied", "discountPaise", "discountBps");

        // the DISCRIMINATOR: exactly what makes the two branches mutually exclusive and `{applied:false}` /
        // `{applied:true,...}` each validate exactly one of them
        Map<String, Object> notAppliedDiscriminator = (Map<String, Object>) properties(notApplied).get("applied");
        assertThat(notAppliedDiscriminator.get("type")).as("not-applied: applied is a boolean").isEqualTo("boolean");
        assertThat((List<Object>) notAppliedDiscriminator.get("enum")).as("not-applied: applied is exactly false")
                .containsExactly(false);
        Map<String, Object> appliedDiscriminator = (Map<String, Object>) properties(applied).get("applied");
        assertThat(appliedDiscriminator.get("type")).as("applied: applied is a boolean").isEqualTo("boolean");
        assertThat((List<Object>) appliedDiscriminator.get("enum")).as("applied: applied is exactly true")
                .containsExactly(true);

        Map<String, Object> discountPaise = (Map<String, Object>) properties(applied).get("discountPaise");
        assertThat(discountPaise.get("type")).isEqualTo("integer");
        assertThat(discountPaise.get("format")).isEqualTo("int64");
        assertThat(discountPaise.get("minimum")).isEqualTo(1);
        Map<String, Object> discountBps = (Map<String, Object>) properties(applied).get("discountBps");
        assertThat(discountBps.get("type")).isEqualTo("integer");
        assertThat(discountBps.get("minimum")).isEqualTo(1);
        assertThat(discountBps.get("maximum")).isEqualTo(10000);

        // the documented fields are exactly the DTO's, and nothing internal is documented anywhere in the preview
        assertThat(properties(applied).keySet()).isEqualTo(components(CheckoutQuoteDto.BenefitPreview.class));
        String text = preview.toString().toLowerCase();
        assertThat(text).doesNotContain("no_rule").doesNotContain("no_membership").doesNotContain("not_eligible")
                .doesNotContain("membershipid").doesNotContain("planid").doesNotContain("planversion")
                .doesNotContain("eligiblesubtotal");
        assertThat(text).contains("advisory").contains("absent");
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_documented_money_preview_is_one_closed_optional_object_of_three_non_negative_int64_amounts() throws IOException {
        Map<String, Object> quote = schema("CustomerCheckoutQuote");
        Map<String, Object> money = (Map<String, Object>) properties(quote).get("moneyPreview");

        assertThat((List<String>) quote.get("required")).as("optional (legacy quotes omit it)")
                .doesNotContain("moneyPreview");
        assertThat(money.get("type")).isEqualTo("object");
        assertThat(money.get("additionalProperties")).as("closed").isEqualTo(false);
        assertThat((List<String>) money.get("required"))
                .containsExactly("merchandiseSubtotalPaise", "benefitDiscountPaise", "payablePaise");
        assertThat(properties(money).keySet())
                .containsExactly("merchandiseSubtotalPaise", "benefitDiscountPaise", "payablePaise");
        for (String field : List.of("merchandiseSubtotalPaise", "benefitDiscountPaise", "payablePaise")) {
            Map<String, Object> f = (Map<String, Object>) properties(money).get(field);
            assertThat(f.get("type")).as(field).isEqualTo("integer");
            assertThat(f.get("format")).as(field).isEqualTo("int64");
            assertThat(f.get("minimum")).as(field).isEqualTo(0);
        }
        assertThat(properties(money).keySet()).as("the documented fields are exactly the DTO's")
                .isEqualTo(components(CheckoutQuoteDto.MoneyPreview.class));

        String text = money.toString().toLowerCase();
        assertThat(text).contains("advisory").contains("absent").contains("not payment authority")
                .contains("not a price lock").contains("authoritative").contains("may differ");
        assertThat(text).doesNotContain("guaranteed final").doesNotContain("charged amount")
                .doesNotContain("captured").doesNotContain("amounttocharge").doesNotContain("paymentstatus")
                .doesNotContain("no_rule").doesNotContain("no_membership").doesNotContain("membershipid")
                .doesNotContain("planid").doesNotContain("eligiblesubtotal");
    }

    @Test
    @SuppressWarnings("unchecked")
    void benefit_preview_is_still_the_two_closed_shapes_and_the_quote_gains_exactly_one_new_property() throws IOException {
        Map<String, Object> quote = schema("CustomerCheckoutQuote");

        assertThat(properties(quote).keySet()).containsExactly("quoteId", "cartVersion", "addressId", "items", "itemCount",
                "distinctItemCount", "subtotalPaise", "currency", "createdAt", "expiresAt", "benefitPreview",
                "moneyPreview", "requestId");
        List<Map<String, Object>> shapes = (List<Map<String, Object>>) ((Map<String, Object>) properties(quote)
                .get("benefitPreview")).get("oneOf");
        assertThat(shapes).hasSize(2);
        assertThat(quote.get("description").toString().toLowerCase()).doesNotContain("no net or payable amount");
    }
}
