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
 * PR-21 -- the documented order/quote money contract and the DTOs cannot drift apart. Reads the hand-maintained
 * {@code docs/api/v1/openapi.yaml} (the generated {@code openapi.json} is pinned by {@code OpenApiExportIT}).
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
    void payable_changed_is_documented_as_an_order_error_with_the_requote_recovery() throws IOException {
        Map<String, Object> envelope = schema("CustomerOrderErrorEnvelope");
        List<String> codes = (List<String>) ((Map<String, Object>) properties(envelope).get("code")).get("enum");
        assertThat(codes).contains("PAYABLE_CHANGED", "PRICE_CHANGED", "QUOTE_EXPIRED");

        String conflict = String.valueOf(response("OrderConflict").get("description"));
        assertThat(conflict).contains("PAYABLE_CHANGED").contains("NO order was created").contains("NEW quote")
                .contains("NEW Idempotency-Key").contains("PRICE_CHANGED");
    }

    @Test
    void the_quote_money_is_documented_as_binding_never_advisory() throws IOException {
        Map<String, Object> quote = schema("CustomerCheckoutQuote");
        String money = String.valueOf(((Map<?, ?>) properties(quote).get("moneyPreview")).get("description"));
        String benefit = String.valueOf(((Map<?, ?>) properties(quote).get("benefitPreview")).get("description"));
        String top = String.valueOf(quote.get("description"));

        assertThat(money).contains("BINDING").contains("PAYABLE_CHANGED").contains("NEW Idempotency-Key")
                .contains("`0` is valid").containsIgnoringCase("not a zero payable");
        for (String text : List.of(money, benefit, top)) {
            assertThat(text).as("the old advisory wording must be gone").doesNotContain("ADVISORY")
                    .doesNotContainIgnoringCase("advisory").doesNotContain("may differ").doesNotContain("can differ")
                    .doesNotContain("NOT a guaranteed final amount");
        }
        assertThat(money).as("no invented component").doesNotContain("taxPaise").doesNotContain("deliveryFeePaise");
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

    // ---------- PR-21 drift protection: the binding-money contract cannot silently regress to "advisory" ----------

    private static final List<String> NON_BINDING = List.of("advisory", "not binding", "non-binding");
    /** "may / can / might ... differ" in any phrasing ("may legitimately differ", "can still differ", ...). */
    private static final java.util.regex.Pattern MAY_DIFFER =
            java.util.regex.Pattern.compile("\\b(may|can|might|could)\\b[^.;]{0,40}?\\bdiffer");

    private static void assertNoMayDiffer(String what, String lower) {
        java.util.regex.Matcher m = MAY_DIFFER.matcher(lower);
        assertThat(m.find() ? m.group() : null).as("%s says the money may differ", what).isNull();
    }

    private static void assertBindingWording(String what, String text, String... required) {
        String lower = text.toLowerCase();
        for (String banned : NON_BINDING) {
            assertThat(lower).as("%s must not describe the money as %s", what, banned).doesNotContain(banned);
        }
        assertNoMayDiffer(what, lower);
        for (String concept : required) {
            assertThat(text).as("%s must state %s", what, concept).containsIgnoringCase(concept);
        }
    }

    @SuppressWarnings("unchecked")
    private static String tagDescription(String tag) throws IOException {
        for (Map<String, Object> t : (List<Map<String, Object>>) doc().get("tags")) {
            if (tag.equals(t.get("name"))) return String.valueOf(t.get("description"));
        }
        throw new AssertionError("tag not documented: " + tag);
    }

    @Test
    void the_checkout_tag_documents_the_quote_money_as_binding() throws IOException {
        assertBindingWording("customer-checkout tag", tagDescription("customer-checkout"),
                "moneyPreview", "BINDING", "PAYABLE_CHANGED");
    }

    @Test
    void the_orders_tag_and_place_operation_say_the_order_commits_the_quote_money_or_refuses() throws IOException {
        assertBindingWording("customer-orders tag", tagDescription("customer-orders"), "money", "PAYABLE_CHANGED");
        @SuppressWarnings("unchecked")
        Map<String, Object> post = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) doc().get("paths"))
                .get("/v1/customer/orders")).get("post");
        assertBindingWording("POST /v1/customer/orders", String.valueOf(post.get("summary")), "moneyPreview", "PAYABLE_CHANGED");
    }

    @Test
    void successful_order_money_is_documented_as_equal_to_the_quote_never_as_possibly_different() throws IOException {
        assertBindingWording("CustomerOrderMoney", String.valueOf(schema("CustomerOrderMoney").get("description")),
                "equals", "moneyPreview");
        assertBindingWording("CustomerOrder", String.valueOf(schema("CustomerOrder").get("description")), "payablePaise");
        assertThat(properties(schema("CustomerOrder"))).containsKey("money");
    }

    /** The checkout/order SOURCE (Javadoc and comments included) must not reintroduce non-binding wording. */
    @Test
    void checkout_and_order_sources_never_call_the_money_advisory_or_non_binding() throws IOException {
        Path checkout = Path.of("src/main/java/com/tazzzo/customer/checkout");
        Path order = Path.of("src/main/java/com/tazzzo/customer/order");
        for (Path dir : List.of(checkout, order)) {
            assertThat(Files.isDirectory(dir)).as("%s reachable from the module directory", dir).isTrue();
            try (var files = Files.walk(dir)) {
                for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                    String lower = Files.readString(f).toLowerCase();
                    for (String banned : NON_BINDING) {
                        assertThat(lower).as("%s contains '%s'", f.getFileName(), banned).doesNotContain(banned);
                    }
                    assertNoMayDiffer(f.getFileName().toString(), lower);
                }
            }
        }
        // the quote DTO's own Javadoc states the binding agreement and its refusal
        String dto = Files.readString(checkout.resolve("CheckoutQuoteDto.java"));
        String javadoc = dto.substring(0, dto.indexOf("public record CheckoutQuoteDto"));
        assertBindingWording("CheckoutQuoteDto Javadoc", javadoc, "BINDING", "PAYABLE_CHANGED", "moneyPreview");
    }
}
