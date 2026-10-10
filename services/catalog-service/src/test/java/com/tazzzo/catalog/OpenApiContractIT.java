package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.api.SurfaceClassifier;
import com.tazzzo.catalog.api.SurfaceClassifier.Surface;
import com.tazzzo.catalog.api.docs.SchemaNameGuard;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generated contract ({@code /v3/api-docs}, exported to {@code docs/openapi.json}) says what the service really does:
 * unique schema names, the right schema per route, success and error statuses per surface, security per surface, typed
 * request bodies, JSON media types, product-id patterns. Documentation-only: nothing here changes runtime behaviour.
 */
class OpenApiContractIT extends AbstractApiIT {

    static final Path YAML = ApiContractParityIT.CONTRACT;
    static final String PRODUCT_ID = com.tazzzo.catalog.domain.ProductIds.REGEX;

    @Autowired SchemaNameGuard guard;
    JsonNode spec;

    @BeforeAll
    void fetch() {
        spec = get("/v3/api-docs", READ_TOKEN, JsonNode.class).getBody();
        assertThat(spec).isNotNull();
    }

    /** method (upper case) + " " + path -> operation node. */
    Map<String, JsonNode> operations() {
        Map<String, JsonNode> out = new TreeMap<>();
        spec.at("/paths").fields().forEachRemaining(p -> p.getValue().fields().forEachRemaining(
                m -> out.put(m.getKey().toUpperCase(Locale.ROOT) + " " + p.getKey(), m.getValue())));
        return out;
    }

    static String norm(String key) {
        return key.replaceAll("\\{[^}]*}", "{}");
    }

    static String path(String key) {
        return key.substring(key.indexOf(' ') + 1);
    }

    static Set<String> statuses(JsonNode op) {
        Set<String> out = new TreeSet<>();
        op.get("responses").fieldNames().forEachRemaining(out::add);
        return out;
    }

    // ------------------------------------------------------------------ M1 unique schema names

    @Test
    void no_two_dto_classes_share_a_schema_name() {
        assertThat(guard.all()).as("the guard saw the DTOs").hasSizeGreaterThan(100);
        assertThat(guard.collisions()).as("one component name must map to exactly one Java class; "
                + "disambiguate with @Schema(name = ...) on the colliding DTOs").isEmpty();
    }

    @Test
    void routes_that_used_to_point_at_the_wrong_schema_reference_their_own() {
        JsonNode products = spec.at("/paths/~1api~1v1~1products/get/responses/200/content/application~1json/schema/oneOf");
        List<String> refs = new ArrayList<>();
        products.forEach(n -> refs.add(n.get("$ref").asText()));
        assertThat(refs).containsExactly("#/components/schemas/ProductResponse", "#/components/schemas/AdminProductListResponse");
        JsonNode adminList = spec.at("/components/schemas/AdminProductListResponse/properties");
        assertThat(adminList.at("/items/items/$ref").asText()).isEqualTo("#/components/schemas/ProductSummary");
        assertThat(adminList.has("nextCursor")).isTrue();

        assertThat(spec.at("/paths/~1api~1v1~1taxonomy~1nodes/get/responses/200/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/AdminNodeListResponse");
        assertThat(spec.at("/components/schemas/AdminNodeListResponse/properties/items/items/$ref").asText())
                .isEqualTo("#/components/schemas/NodeResponse");

        assertThat(spec.at("/paths/~1api~1v1~1admin~1orders/get/responses/200/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/StaffOrderPage");
        assertThat(spec.at("/components/schemas/StaffOrderPage/properties/items/items/$ref").asText())
                .isEqualTo("#/components/schemas/StaffOrder");
        assertThat(spec.at("/paths/~1api~1v1~1admin~1support~1cases/get/responses/200/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/StaffSupportPage");
        assertThat(spec.at("/components/schemas/StaffSupportPage/properties/items/items/$ref").asText())
                .isEqualTo("#/components/schemas/StaffSummary");

        assertThat(spec.at("/paths/~1catalog~1v1~1categories/get/responses/200/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/ConsumerNodeListResponse");
        assertThat(spec.at("/paths/~1v1~1categories/get/responses/200/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/CommerceNodeListResponse");
        assertThat(spec.at("/paths/~1catalog~1v1~1categories~1{nodeId}~1products/get/responses/200/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/ConsumerProductListResponse");
        for (String bare : new String[]{"Item", "NodeListResponse", "ProductListResponse", "StaffPage", "VersionRequest"}) {
            assertThat(spec.at("/components/schemas").has(bare)).as("ambiguous schema name " + bare + " is gone").isFalse();
        }
    }

    @Test
    void every_reference_resolves() {
        Set<String> dangling = new TreeSet<>();
        collectRefs(spec, dangling);
        assertThat(dangling).as("dangling $ref").isEmpty();
    }

    private void collectRefs(JsonNode node, Set<String> dangling) {
        if (node.isObject()) {
            if (node.has("$ref") && node.get("$ref").isTextual()) {
                String ref = node.get("$ref").asText();
                if (spec.at(ref.substring(1)).isMissingNode()) dangling.add(ref);
            }
            node.elements().forEachRemaining(n -> collectRefs(n, dangling));
        } else if (node.isArray()) {
            node.forEach(n -> collectRefs(n, dangling));
        }
    }

    // ------------------------------------------------------------------ M2 the shared admin product route

    @Test
    void the_admin_product_list_and_the_canonical_key_lookup_are_one_documented_operation() {
        JsonNode get = spec.at("/paths/~1api~1v1~1products/get");
        assertThat(get.get("operationId").asText()).as("operationId stays stable").isEqualTo("productGetByCanonicalKey");
        Map<String, JsonNode> params = new TreeMap<>();
        get.get("parameters").forEach(p -> params.put(p.get("name").asText(), p));
        assertThat(params.keySet()).containsExactlyInAnyOrder("canonicalKey", "verticalId", "lifecycle", "status", "cursor", "limit");
        assertThat(params.values()).allSatisfy(p -> {
            assertThat(p.get("in").asText()).isEqualTo("query");
            assertThat(p.get("required").asBoolean()).as(p.get("name").asText() + " is optional").isFalse();
        });
        assertThat(params.get("limit").at("/schema/maximum").asInt()).isEqualTo(200);
        assertThat(get.at("/responses/200/description").asText()).contains("ProductResponse").contains("AdminProductListResponse");
        assertThat(get.get("description").asText()).contains("canonicalKey").contains("keyset");
    }

    // ------------------------------------------------------------------ M3 statuses and error responses

    /** Independent of the customizer: the non-200 success statuses each handler really returns. */
    static final Map<String, Set<String>> SUCCESS = Map.ofEntries(
            Map.entry("POST /api/v1/products", Set.of("201")),
            Map.entry("POST /api/v1/products/{}/merge/{}", Set.of("202")),
            Map.entry("POST /api/v1/taxonomy/releases", Set.of("201")),
            Map.entry("POST /api/v1/taxonomy/nodes", Set.of("201")),
            Map.entry("POST /api/v1/attributes", Set.of("201")),
            Map.entry("POST /api/v1/evidence", Set.of("200", "201")),
            Map.entry("POST /api/v1/evidence/{}/retract", Set.of("202")),
            Map.entry("PUT /api/v1/admin/delivery-slots/{}/{}", Set.of("200", "201")),
            Map.entry("PUT /api/v1/admin/inventory/{}/{}", Set.of("200", "201")),
            Map.entry("PUT /api/v1/admin/prices/{}", Set.of("200", "201")),
            Map.entry("PUT /api/v1/admin/service-areas/{}", Set.of("200", "201")),
            Map.entry("PUT /api/v1/admin/media/{}/{}", Set.of("200", "201")),
            Map.entry("POST /api/v1/admin/media/uploads", Set.of("201")),
            Map.entry("POST /api/v1/admin/content/blocks", Set.of("201")),
            Map.entry("POST /api/v1/admin/content/uploads", Set.of("201")),
            Map.entry("POST /api/v1/admin/imports/jobs", Set.of("201")),
            Map.entry("POST /v1/auth/otp/request", Set.of("202")),
            Map.entry("POST /v1/auth/logout", Set.of("204")),
            Map.entry("POST /v1/customer/addresses", Set.of("201")),
            Map.entry("DELETE /v1/customer/addresses/{}", Set.of("204")),
            Map.entry("POST /v1/customer/support/cases", Set.of("201")));

    @Test
    void success_statuses_are_the_ones_the_handlers_return() {
        List<String> wrong = new ArrayList<>();
        int declared = 0;
        for (Map.Entry<String, JsonNode> op : operations().entrySet()) {
            Set<String> success = new TreeSet<>();
            statuses(op.getValue()).stream().filter(s -> s.startsWith("2")).forEach(success::add);
            Set<String> expected = SUCCESS.getOrDefault(norm(op.getKey()), Set.of("200"));
            if (!success.equals(expected)) wrong.add(op.getKey() + " documents " + success + " but returns " + expected);
            if (!expected.equals(Set.of("200"))) declared++;
        }
        assertThat(wrong).isEmpty();
        assertThat(declared).as("every non-200 table entry matched an operation").isEqualTo(SUCCESS.size());
    }

    @Test
    void every_operation_documents_its_surface_errors_with_the_envelope_that_surface_writes() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : operations().entrySet()) {
            String path = path(e.getKey());
            JsonNode op = e.getValue();
            Surface surface = SurfaceClassifier.classify(path);
            Set<String> st = statuses(op);
            boolean body = op.has("requestBody");
            switch (surface) {
                case INTERNAL -> {
                    for (String s : List.of("400", "401", "403", "500")) if (!st.contains(s)) problems.add(e.getKey() + " lacks " + s);
                    if (body && !st.contains("413")) problems.add(e.getKey() + " has a body but no 413");
                    if (!e.getKey().startsWith("GET ") && !st.contains("409")) problems.add(e.getKey() + " writes but has no 409");
                    for (String s : st) {
                        if (s.compareTo("400") < 0) continue;
                        String ref = op.at("/responses/" + s + "/content/application~1json/schema/$ref").asText();
                        if (!ref.equals("#/components/schemas/AdminErrorEnvelope")) problems.add(e.getKey() + " " + s + " -> " + ref);
                    }
                }
                case PUBLIC_CONSUMER, CUSTOMER_AUTHENTICATED -> {
                    if (st.stream().noneMatch(s -> s.compareTo("400") >= 0)) problems.add(e.getKey() + " documents no error");
                    if (body && !st.contains("413")) problems.add(e.getKey() + " has a body but no 413");
                    if (st.contains("413")) {
                        assertThat(op.at("/responses/413/content/application~1json/schema/$ref").asText())
                                .isEqualTo("#/components/schemas/PlatformErrorEnvelope");
                    }
                    if (path.startsWith("/catalog/v1/")) {
                        assertThat(op.at("/responses/404/content/application~1json/schema/$ref").asText())
                                .as(e.getKey()).isEqualTo("#/components/schemas/PlatformErrorEnvelope");
                    }
                }
                default -> { }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void envelope_components_describe_the_real_bodies() {
        JsonNode c = spec.at("/components/schemas");
        assertThat(c.at("/AdminErrorEnvelope/required").toString()).isEqualTo("[\"error\"]");
        assertThat(c.at("/AdminErrorEnvelope/properties/error/$ref").asText()).isEqualTo("#/components/schemas/AdminErrorBody");
        assertThat(c.at("/AdminErrorBody/required").toString()).contains("code").contains("message").contains("request_id");
        assertThat(c.at("/PlatformErrorEnvelope/properties").has("request_id")).isTrue();
        assertThat(c.at("/PlatformErrorEnvelope/properties").has("requestId")).isFalse();
        for (String camel : new String[]{"V1ErrorEnvelope", "V1PublicReadErrorEnvelope", "V1OtpErrorEnvelope", "V1CheckoutErrorEnvelope"}) {
            assertThat(c.at("/" + camel + "/properties").has("requestId")).as(camel).isTrue();
            assertThat(c.at("/" + camel + "/properties").has("request_id")).as(camel).isFalse();
        }
    }

    /** The /v1 statuses in the generated spec are exactly the ones the hand-written app contract documents. */
    @Test
    @SuppressWarnings("unchecked")
    void generated_v1_statuses_equal_the_hand_written_contract() throws IOException {
        Map<String, Object> yaml = new Yaml().load(Files.readString(YAML));
        Map<String, Set<String>> documented = new TreeMap<>();
        ((Map<String, Object>) yaml.get("paths")).forEach((p, item) -> {
            if (!p.startsWith("/v1/")) return;
            ((Map<String, Object>) item).forEach((m, op) -> {
                if (!ApiContractParityIT.HTTP.contains(m)) return;
                Set<String> codes = new TreeSet<>();
                ((Map<Object, Object>) ((Map<String, Object>) op).get("responses")).keySet().forEach(k -> codes.add(String.valueOf(k)));
                documented.put(m.toUpperCase(Locale.ROOT) + " " + norm(p), codes);
            });
        });
        Map<String, Set<String>> generated = new TreeMap<>();
        operations().forEach((k, op) -> {
            if (path(k).startsWith("/v1/")) generated.put(norm(k), statuses(op));
        });
        assertThat(documented).hasSizeGreaterThan(35);
        assertThat(generated).as("the same /v1 operations with the same status sets").isEqualTo(documented);
    }

    @Test
    void the_operation_table_is_exactly_the_published_operations() {
        // 135 operations were generated before this change and the same 135 remain
        assertThat(operations()).hasSize(135);
        assertThat(operations().values()).allSatisfy(op -> assertThat(op.get("operationId").asText()).matches("^[a-z][A-Za-z0-9]*$"));
    }

    // ------------------------------------------------------------------ M4 security, request bodies, required

    @Test
    void documented_security_matches_the_surface_of_every_operation() {
        JsonNode schemes = spec.at("/components/securitySchemes");
        assertThat(schemes.get("adminBearer").get("scheme").asText()).isEqualTo("bearer");
        assertThat(schemes.get("customerBearer").get("scheme").asText()).isEqualTo("bearer");
        assertThat(schemes.get("trustedCallerName").get("name").asText()).isEqualTo("X-Tazzzo-Caller");
        assertThat(schemes.get("trustedCallerSecret").get("name").asText()).isEqualTo("X-Tazzzo-Caller-Secret");
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : operations().entrySet()) {
            String path = path(e.getKey());
            Surface surface = SurfaceClassifier.classify(path);
            String expected = switch (surface) {
                case INTERNAL -> "[{\"adminBearer\":[]}]";
                case CUSTOMER_AUTHENTICATED -> "[{\"customerBearer\":[]}]";
                case HEALTH -> "[]";
                case PUBLIC_CONSUMER -> path.equals("/v1/auth/logout") ? "[{\"customerBearer\":[]}]"   // verified inside the controller
                        : path.startsWith("/v1/auth/") ? "[]"
                        : "[{},{\"trustedCallerName\":[],\"trustedCallerSecret\":[]}]";
                case UNKNOWN -> "<must not be served>";
            };
            JsonNode security = e.getValue().get("security");
            if (security == null || !security.toString().equals(expected)) {
                wrong.add(e.getKey() + " is " + surface + ": documented " + security + ", expected " + expected);
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    void no_request_body_is_an_untyped_json_node() {
        assertThat(spec.at("/components/schemas").has("JsonNode")).isFalse();
        assertThat(spec.toString()).doesNotContain("schemas/JsonNode");
        Map<String, String> typed = Map.ofEntries(
                Map.entry("POST /api/v1/admin/orders/{orderId}/transition", "StaffOrderTransitionRequest"),
                Map.entry("POST /api/v1/admin/support/cases/{caseId}/messages", "SupportReplyRequest"),
                Map.entry("POST /api/v1/admin/support/cases/{caseId}/assign", "StaffCaseAssignRequest"),
                Map.entry("POST /api/v1/admin/support/cases/{caseId}/status", "StaffCaseStatusRequest"),
                Map.entry("PUT /v1/customer/cart/items/{skuId}", "CartSetItemRequest"),
                Map.entry("POST /v1/customer/checkout/quote", "CheckoutQuoteRequest"),
                Map.entry("POST /v1/customer/orders", "PlaceOrderRequest"),
                Map.entry("POST /v1/customer/orders/{orderId}/cancel", "CancelOrderRequest"),
                Map.entry("PATCH /v1/customer/profile", "CustomerProfilePatchRequest"),
                Map.entry("PATCH /v1/customer/addresses/{addressId}", "AddressPatchRequest"),
                Map.entry("POST /v1/customer/support/cases", "CustomerSupportOpenRequest"),
                Map.entry("POST /v1/customer/support/cases/{caseId}/messages", "SupportReplyRequest"),
                Map.entry("POST /v1/customer/account/deletion", "AccountDeletionRequest"));
        Map<String, JsonNode> ops = operations();
        typed.forEach((key, schema) -> {
            JsonNode op = ops.entrySet().stream().filter(o -> norm(o.getKey()).equals(norm(key))).findFirst().orElseThrow().getValue();
            assertThat(op.at("/requestBody/content/application~1json/schema/$ref").asText()).as(key)
                    .isEqualTo("#/components/schemas/" + schema);
            assertThat(op.at("/requestBody/required").asBoolean()).as(key).isTrue();
        });
        assertThat(spec.at("/components/schemas/StaffOrderTransitionRequest/required").toString()).contains("to").contains("expectedVersion");
        assertThat(spec.at("/components/schemas/PlaceOrderRequest/required").toString()).contains("quoteId").contains("paymentMethod");
    }

    @Test
    void primitive_record_properties_of_responses_are_required_but_request_primitives_are_not_forced() {
        // response records: a primitive is always serialised
        assertThat(spec.at("/components/schemas/ProductSummary/required").toString()).contains("version");
        assertThat(spec.at("/components/schemas/StockResponse/required").toString()).contains("onHand").contains("version");
        int withRequired = 0;
        var names = spec.at("/components/schemas").fieldNames();
        while (names.hasNext()) {
            if (spec.at("/components/schemas/" + names.next()).has("required")) withRequired++;
        }
        assertThat(withRequired).as("schemas that declare required properties").isGreaterThan(40);
        // request records: an absent primitive silently becomes 0/false when read, so it is NOT required by this rule
        assertThat(spec.at("/components/schemas/PriceRequest").has("required")).isFalse();
    }

    // ------------------------------------------------------------------ L-list

    @Test
    void responses_are_json_and_the_csv_download_says_so() {
        List<String> star = new ArrayList<>();
        operations().forEach((k, op) -> op.get("responses").fields().forEachRemaining(r -> {
            JsonNode content = r.getValue().get("content");
            if (content != null && content.has("*/*")) star.add(k + " " + r.getKey());
        }));
        assertThat(star).isEmpty();
        JsonNode csv = spec.at("/paths/~1api~1v1~1admin~1imports~1jobs~1{id}~1errors.csv/get/responses/200/content");
        assertThat(csv.has("text/csv")).isTrue();
        JsonNode rows = spec.at("/paths/~1api~1v1~1admin~1imports~1jobs~1{id}~1rows/post/requestBody/content");
        assertThat(rows.has("application/json")).isTrue();
        assertThat(rows.has("text/csv")).as("the hidden text/csv overload is documented on the JSON operation").isTrue();
    }

    @Test
    void the_product_id_pattern_is_published_everywhere_a_product_or_sku_id_is_accepted() {
        assertThat(paramPattern("/v1/products/{id}", "get", "id")).isEqualTo(PRODUCT_ID);
        assertThat(paramPattern("/catalog/v1/products/{productId}", "get", "productId")).isEqualTo(PRODUCT_ID);
        assertThat(paramPattern("/v1/customer/cart/items/{skuId}", "put", "skuId")).isEqualTo(PRODUCT_ID);
        assertThat(paramPattern("/v1/customer/cart/items/{skuId}", "delete", "skuId")).isEqualTo(PRODUCT_ID);
        assertThat(paramPattern("/api/v1/admin/media/{ownerType}/{ownerId}", "get", "ownerId")).isEqualTo(PRODUCT_ID);
        assertThat(paramPattern("/api/v1/admin/media/{ownerType}/{ownerId}", "put", "ownerId")).isEqualTo(PRODUCT_ID);
        assertThat(spec.at("/components/schemas/PriceRow/properties/skuId/pattern").asText()).isEqualTo(PRODUCT_ID);
        assertThat(spec.at("/components/schemas/StockRow/properties/skuId/pattern").asText()).isEqualTo(PRODUCT_ID);
        assertThat(spec.at("/components/schemas/PayloadDto/properties/ids/items/pattern").asText()).isEqualTo(PRODUCT_ID);
        assertThat(spec.at("/components/schemas/UploadRequest/properties/ownerId/pattern").asText()).isEqualTo(PRODUCT_ID);
    }

    @Test
    void the_media_owner_type_is_an_enum() {
        for (String m : new String[]{"get", "put"}) {
            JsonNode op = spec.at("/paths/~1api~1v1~1admin~1media~1{ownerType}~1{ownerId}/" + m);
            for (JsonNode p : op.get("parameters")) {
                if (p.get("name").asText().equals("ownerType")) {
                    assertThat(p.at("/schema/enum").toString()).isEqualTo("[\"product\",\"sku\"]");
                }
            }
        }
        assertThat(spec.at("/components/schemas/UploadRequest/properties/ownerType/enum").toString()).isEqualTo("[\"product\",\"sku\"]");
    }

    @Test
    void the_body_bounds_are_documented() {
        String info = spec.at("/info/description").asText();
        assertThat(info).contains("64 KiB (65536 bytes)").contains("2 MiB (2097152 bytes)").contains("16 MiB").contains("413");
        assertThat(spec.at("/paths/~1api~1v1~1admin~1imports~1products/post/description").asText()).contains("2 MiB");
        assertThat(spec.at("/paths/~1api~1v1~1products/post/description").asText()).contains("64 KiB");
        assertThat(spec.at("/paths/~1api~1v1~1admin~1imports~1products/post/responses/413/description").asText()).contains("2 MiB");
    }

    private String paramPattern(String path, String method, String name) {
        for (JsonNode p : spec.at("/paths").get(path).get(method).get("parameters")) {
            if (name.equals(p.get("name").asText())) return p.at("/schema/pattern").asText();
        }
        throw new AssertionError("no parameter " + name + " on " + method + " " + path);
    }
}
