package com.tazzzo.customer.order;

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
 * The documented public Order money contract and the DTO cannot drift apart, and the documented semantics stay those
 * ratified: the Order's {@code money} is AUTHORITATIVE and may differ from the quote's ADVISORY {@code moneyPreview}; there
 * is no {@code PAYABLE_CHANGED} error. Reads the hand-maintained {@code docs/api/v1/openapi.yaml} structurally (the generated
 * {@code openapi.json} is pinned by {@code OpenApiExportIT}).
 */
class OrderContractTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> doc() throws IOException {
        Path file = Path.of("../../docs/api/v1/openapi.yaml");
        assertThat(Files.exists(file)).as("openapi.yaml reachable from the module directory").isTrue();
        try (FileInputStream in = new FileInputStream(file.toFile())) {
            return new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(String name) throws IOException {
        return (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) doc().get("components")).get("schemas")).get(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> response(String name) throws IOException {
        return (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) doc().get("components")).get("responses")).get(name);
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
    void the_documented_order_properties_match_the_dto_and_money_is_optional() throws IOException {
        Map<String, Object> order = schema("CustomerOrder");

        assertThat(properties(order).keySet()).isEqualTo(components(CustomerOrderDto.class));
        assertThat((List<String>) order.get("required")).as("absent on a legacy order: never a zero payable")
                .doesNotContain("money").contains("subtotalPaise", "orderId", "requestId");
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_documented_order_money_is_exactly_the_three_v1_amounts_all_required_and_closed() throws IOException {
        Map<String, Object> money = schema("CustomerOrderMoney");

        assertThat(properties(money).keySet()).isEqualTo(components(CustomerOrderDto.OrderMoney.class))
                .containsExactlyInAnyOrder("merchandiseSubtotalPaise", "benefitDiscountPaise", "payablePaise");
        assertThat((List<String>) money.get("required")).containsExactlyInAnyOrder(
                "merchandiseSubtotalPaise", "benefitDiscountPaise", "payablePaise");
        assertThat(money.get("additionalProperties")).isEqualTo(false);
        for (Object p : properties(money).values()) {
            assertThat(((Map<String, Object>) p).get("format")).as("integer paise").isEqualTo("int64");
            assertThat(((Map<String, Object>) p).get("minimum")).isEqualTo(0);
        }
        assertThat(String.valueOf(money.get("description"))).contains("ABSENT").contains("NEVER a zero payable");
    }

    @Test
    @SuppressWarnings("unchecked")
    void post_quote_documents_the_replay_after_expiry_410() throws IOException {
        Map<String, Object> paths = (Map<String, Object>) doc().get("paths");
        Map<String, Object> post = (Map<String, Object>) ((Map<String, Object>) paths.get("/v1/customer/checkout/quote")).get("post");
        Map<String, Object> responses = (Map<String, Object>) post.get("responses");

        assertThat(responses.keySet().stream().map(String::valueOf).toList()).contains("200", "409", "410", "412", "428");
        assertThat(String.valueOf(post.get("summary"))).contains("410 QUOTE_EXPIRED").contains("never 200");
    }

    @Test
    @SuppressWarnings("unchecked")
    void there_is_no_payable_changed_order_error_and_the_conflict_response_does_not_mention_one() throws IOException {
        Map<String, Object> envelope = schema("CustomerOrderErrorEnvelope");
        List<String> codes = (List<String>) ((Map<String, Object>) properties(envelope).get("code")).get("enum");

        assertThat(codes).contains("PRICE_CHANGED", "QUOTE_EXPIRED").doesNotContain("PAYABLE_CHANGED");
        assertThat(String.valueOf(response("OrderConflict").get("description"))).doesNotContain("PAYABLE_CHANGED");
        assertThat(java.util.Arrays.stream(OrderFailure.Reason.values()).map(Enum::name).toList())
                .doesNotContain("PAYABLE_CHANGED");
    }

    @Test
    void the_order_money_is_documented_as_authoritative_and_allowed_to_differ_from_the_advisory_quote() throws IOException {
        String money = String.valueOf(schema("CustomerOrderMoney").get("description"));
        String preview = String.valueOf(((Map<?, ?>) properties(schema("CustomerCheckoutQuote")).get("moneyPreview"))
                .get("description"));

        assertThat(money).contains("AUTHORITATIVE").contains("may differ").contains("advisory `moneyPreview`")
                .doesNotContainIgnoringCase("binding").doesNotContain("always equals");
        assertThat(preview).contains("ADVISORY").doesNotContainIgnoringCase("binding customer money")
                .doesNotContain("PAYABLE_CHANGED");
    }
}
