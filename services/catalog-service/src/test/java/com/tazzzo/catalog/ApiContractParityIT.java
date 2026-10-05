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
        paths.forEach((path, item) -> ((Map<String, Object>) item).keySet().stream().filter(HTTP::contains)
                .forEach(m -> out.add(m.toUpperCase(Locale.ROOT) + " " + norm(path))));
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

    /** Every documented 4xx/5xx of every /v1 operation is a JSON error body that requires a code and a message. */
    @Test
    @SuppressWarnings("unchecked")
    void every_documented_v1_error_is_a_json_envelope_with_a_code_and_a_message() throws IOException {
        Map<String, Object> doc = new Yaml().load(Files.readString(CONTRACT));
        Set<String> bad = new TreeSet<>();
        int checked = 0;
        for (Map.Entry<String, Object> path : ((Map<String, Object>) doc.get("paths")).entrySet()) {
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
}
