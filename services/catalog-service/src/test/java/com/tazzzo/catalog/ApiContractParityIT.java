package com.tazzzo.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The app-facing contract {@code docs/api/v1/openapi.yaml} and the running controllers describe the SAME {@code /v1/**}
 * surface, operation by operation: every served method+path is documented, and every documented one is served. Path
 * variables compare by position, not name. The admin/internal {@code /api/v1/**} surface is generated into
 * {@code docs/openapi.json} by {@link OpenApiExportIT} and is not hand-written, so it is out of scope here.
 */
class ApiContractParityIT extends AbstractApiIT {

    static final Path CONTRACT = Path.of("../../docs/api/v1/openapi.yaml");
    static final Set<String> HTTP = Set.of("get", "put", "post", "delete", "patch", "head", "options");

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    static String norm(String path) {
        return path.replaceAll("\\{[^}]*}", "{}");
    }

    Set<String> served() {
        Set<String> out = new TreeSet<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            Set<String> patterns = info.getPathPatternsCondition() != null
                    ? info.getPathPatternsCondition().getPatternValues() : info.getPatternValues();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String p : patterns) {
                if (!p.startsWith("/v1/")) continue;
                if (methods.isEmpty()) {
                    out.add("ANY " + norm(p));
                }
                for (RequestMethod m : methods) {
                    out.add(m.name() + " " + norm(p));
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Set<String> documented() throws IOException {
        Map<String, Object> doc = new Yaml().load(Files.readString(CONTRACT));
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        Set<String> out = new TreeSet<>();
        paths.entrySet().stream().filter(e -> e.getKey().startsWith("/v1/")).forEach(e -> ((Map<String, Object>) e.getValue())
                .keySet().stream().filter(HTTP::contains)
                .forEach(m -> out.add(m.toUpperCase(Locale.ROOT) + " " + norm(e.getKey()))));
        return out;
    }

    @Test
    void every_served_v1_operation_is_documented_and_every_documented_one_is_served() throws IOException {
        Set<String> served = served();
        Set<String> documented = documented();
        assertThat(served).as("the app surface is non-trivial").hasSizeGreaterThan(10);

        Set<String> undocumented = new TreeSet<>(served);
        undocumented.removeAll(documented);
        Set<String> phantom = new TreeSet<>(documented);
        phantom.removeAll(served);
        assertThat(undocumented).as("served but missing from docs/api/v1/openapi.yaml").isEmpty();
        assertThat(phantom).as("documented in docs/api/v1/openapi.yaml but not served").isEmpty();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> resolve(Map<String, Object> root, Map<String, Object> node) {
        int hops = 0;
        while (node != null && node.containsKey("$ref")) {
            if (++hops > 10) throw new IllegalStateException("$ref cycle");
            Object cur = root;
            for (String part : ((String) node.get("$ref")).substring(2).split("/")) {
                cur = ((Map<String, Object>) cur).get(part);
            }
            node = (Map<String, Object>) cur;
        }
        return node;
    }

    /**
     * The product id grammar is published three ways (hand-written YAML ProductId and cart skuId, generated create body and
     * admin path params); every one must be exactly the Java constant, so the contract cannot drift from enforcement.
     */
    @Test
    @SuppressWarnings("unchecked")
    void the_published_product_id_patterns_equal_the_java_grammar() throws IOException {
        String expected = com.tazzzo.catalog.domain.ProductIds.REGEX;
        Map<String, Object> doc = new Yaml().load(Files.readString(CONTRACT));
        Map<String, Object> productId = ((Map<String, Map<String, Object>>) ((Map<String, Object>) doc.get("components")).get("parameters"))
                .get("ProductId");
        assertThat(((Map<String, Object>) productId.get("schema")).get("pattern")).as("YAML ProductId").isEqualTo(expected);
        Map<String, Object> cartItem = (Map<String, Object>) ((Map<String, Object>) doc.get("paths")).get("/v1/customer/cart/items/{skuId}");
        int skuParams = 0;
        for (Map.Entry<String, Object> op : cartItem.entrySet()) {
            if (!HTTP.contains(op.getKey())) continue;
            for (Object o : (java.util.List<Object>) ((Map<String, Object>) op.getValue()).get("parameters")) {
                Map<String, Object> param = resolve(doc, (Map<String, Object>) o);
                if (!"skuId".equals(param.get("name"))) continue;
                skuParams++;
                assertThat(((Map<String, Object>) param.get("schema")).get("pattern")).as("YAML cart skuId " + op.getKey()).isEqualTo(expected);
            }
        }
        assertThat(skuParams).as("cart skuId path params checked").isGreaterThanOrEqualTo(2);

        com.fasterxml.jackson.databind.JsonNode spec = get("/v3/api-docs", READ_TOKEN, com.fasterxml.jackson.databind.JsonNode.class).getBody();
        assertThat(spec.at("/components/schemas/CreateProductRequest/properties/id/pattern").asText()).as("create id").isEqualTo(expected);
        assertThat(spec.at("/components/schemas/BundleComponentDto/properties/componentProductId/pattern").asText()).isEqualTo(expected);
        assertThat(spec.at("/components/schemas/PackOfDto/properties/componentProductId/pattern").asText()).isEqualTo(expected);
        assertThat(pathParamPattern(spec, "/api/v1/products/{id}", "get", "id")).as("admin product id").isEqualTo(expected);
        assertThat(pathParamPattern(spec, "/api/v1/products/{id}/classify", "post", "id")).isEqualTo(expected);
        assertThat(pathParamPattern(spec, "/api/v1/admin/prices/{skuId}", "get", "skuId")).as("admin price skuId").isEqualTo(expected);
        assertThat(pathParamPattern(spec, "/api/v1/admin/inventory/{skuId}/{locationId}", "get", "skuId")).as("admin inventory skuId").isEqualTo(expected);
    }

    private static String pathParamPattern(com.fasterxml.jackson.databind.JsonNode spec, String path, String method, String name) {
        for (com.fasterxml.jackson.databind.JsonNode p : spec.at("/paths").get(path).get(method).get("parameters")) {
            if (name.equals(p.get("name").asText())) return p.at("/schema/pattern").asText();
        }
        throw new AssertionError("no parameter " + name + " on " + method + " " + path);
    }

    /** Every documented 4xx/5xx of every /v1 operation is a JSON error body that requires a code and a message. */
    @Test
    @SuppressWarnings("unchecked")
    void every_documented_v1_error_is_a_json_envelope_with_a_code_and_a_message() throws IOException {
        Map<String, Object> doc = new Yaml().load(Files.readString(CONTRACT));
        Set<String> bad = new TreeSet<>();
        int checked = 0;
        for (Map.Entry<String, Object> path : ((Map<String, Object>) doc.get("paths")).entrySet()) {
            if (!path.getKey().startsWith("/v1/")) continue;   // infrastructure probes (e.g. /health/*) have their own bodies
            for (Map.Entry<String, Object> op : ((Map<String, Object>) path.getValue()).entrySet()) {
                if (!HTTP.contains(op.getKey())) continue;
                Map<String, Object> responses = (Map<String, Object>) ((Map<String, Object>) op.getValue()).get("responses");
                for (Map.Entry<String, Object> r : responses.entrySet()) {
                    String status = String.valueOf(r.getKey());
                    if (!status.matches("[45]\\d\\d")) continue;
                    String where = op.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey() + " " + status;
                    Map<String, Object> response = resolve(doc, (Map<String, Object>) r.getValue());
                    Map<String, Object> content = (Map<String, Object>) response.get("content");
                    Map<String, Object> json = content == null ? null : (Map<String, Object>) content.get("application/json");
                    Map<String, Object> schema = json == null ? null : resolve(doc, (Map<String, Object>) json.get("schema"));
                    Object required = schema == null ? null : schema.get("required");
                    checked++;
                    if (!(required instanceof java.util.List<?> req) || !req.contains("code") || !req.contains("message")) {
                        bad.add(where);
                    }
                }
            }
        }
        assertThat(checked).as("error responses inspected").isGreaterThan(50);
        assertThat(bad).as("error responses without a JSON {code, message} body").isEmpty();
    }

    /** Every documented operation can be addressed by a generated client: an id, unique across the file. */
    @Test
    @SuppressWarnings("unchecked")
    void every_documented_operation_has_a_unique_operation_id() throws IOException {
        Map<String, Object> doc = new Yaml().load(Files.readString(CONTRACT));
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.List<String> missing = new java.util.ArrayList<>();
        ((Map<String, Object>) doc.get("paths")).forEach((p, item) -> ((Map<String, Object>) item).forEach((m, op) -> {
            if (!HTTP.contains(m)) return;
            Object id = ((Map<String, Object>) op).get("operationId");
            if (id == null) missing.add(m.toUpperCase(Locale.ROOT) + " " + p);
            else ids.add(String.valueOf(id));
        }));
        assertThat(missing).as("operations without operationId").isEmpty();
        assertThat(ids).doesNotHaveDuplicates();
    }

    /** CustomerRateLimitFilter covers every /v1/customer/** route, so every one of them documents 429; every body has its 413. */
    @Test
    @SuppressWarnings("unchecked")
    void customer_operations_document_429_and_body_operations_document_413() throws IOException {
        Map<String, Object> doc = new Yaml().load(Files.readString(CONTRACT));
        Set<String> no429 = new TreeSet<>();
        Set<String> no413 = new TreeSet<>();
        ((Map<String, Object>) doc.get("paths")).forEach((p, item) -> ((Map<String, Object>) item).forEach((m, op) -> {
            if (!HTTP.contains(m) || !p.startsWith("/v1/")) return;
            Map<Object, Object> responses = (Map<Object, Object>) ((Map<String, Object>) op).get("responses");
            Set<String> codes = new TreeSet<>();
            responses.keySet().forEach(k -> codes.add(String.valueOf(k)));
            String where = m.toUpperCase(Locale.ROOT) + " " + p;
            if (p.startsWith("/v1/customer/") && !codes.contains("429")) no429.add(where);
            if (((Map<String, Object>) op).containsKey("requestBody") && !codes.contains("413")) no413.add(where);
        }));
        assertThat(no429).as("customer operations without 429").isEmpty();
        assertThat(no413).as("operations with a request body but no 413").isEmpty();
    }

    /** Every code the order error handler can write is in the documented enum, and the cancel route's 409 is its own response. */
    @Test
    @SuppressWarnings("unchecked")
    void order_error_codes_written_by_the_handler_are_all_in_the_documented_enum() throws IOException {
        String handler = Files.readString(Path.of("src/main/java/com/tazzzo/customer/order/OrderExceptionHandler.java"));
        Set<String> written = new TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("HttpStatus\\.[A-Z_]+,\\s*\"([A-Z][A-Z_]+)\"").matcher(handler);
        while (m.find()) written.add(m.group(1));
        assertThat(written).as("the handler's code literals were found").contains("ORDER_NOT_CANCELLABLE", "STALE_VERSION", "INTERNAL");
        Map<String, Object> doc = new Yaml().load(Files.readString(CONTRACT));
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("schemas");
        java.util.List<String> enumValues = (java.util.List<String>) ((Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) schemas.get("CustomerOrderErrorEnvelope")).get("properties")).get("code")).get("enum");
        assertThat(enumValues).containsAll(written);

        Map<String, Object> cancel = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) doc.get("paths"))
                .get("/v1/customer/orders/{orderId}/cancel")).get("post");
        Map<Object, Object> responses = (Map<Object, Object>) cancel.get("responses");
        assertThat(((Map<String, Object>) responses.get("409")).get("$ref")).isEqualTo("#/components/responses/OrderCancelConflict");
    }
}
