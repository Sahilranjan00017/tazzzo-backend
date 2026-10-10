package com.tazzzo.catalog.api;

import io.swagger.v3.oas.annotations.Operation;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Stable, explicit OpenAPI {@code operationId}s (ADR-017).
 *
 * <p>springdoc names an operation after its handler method and de-duplicates collisions with a numeric suffix
 * ({@code get_1}, {@code list_7}) in registration order, so adding a controller or an overload silently renumbered
 * existing ids and broke generated clients. This customizer replaces every auto-generated id with
 * {@code lowerCamel(Controller sans "Controller") + CapitalisedMethod} (for example
 * {@code ProductController.create} becomes {@code productCreate}). Handler methods that declare an explicit
 * {@code @Operation(operationId = ...)} are left untouched. Overloads (same controller, same method name) are
 * disambiguated by HTTP method, path and consumed media type, never by order. The result depends only on code, not on scan order.
 */
@Component
public class StableOperationIds implements OperationCustomizer {

    private final ObjectProvider<RequestMappingHandlerMapping> mappings;

    public StableOperationIds(
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mappings) {
        this.mappings = mappings;
    }

    @Override
    public io.swagger.v3.oas.models.Operation customize(io.swagger.v3.oas.models.Operation operation,
                                                         HandlerMethod handlerMethod) {
        Operation explicit = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), Operation.class);
        if (explicit != null && !explicit.operationId().isBlank()) {
            return operation;
        }
        Class<?> controller = ClassUtils.getUserClass(handlerMethod.getBeanType());
        String name = handlerMethod.getMethod().getName();
        String id = baseId(controller, name);
        RequestMappingHandlerMapping mapping = mappings.getIfAvailable();
        if (mapping != null) {
            List<RequestMappingInfo> own = new ArrayList<>();
            boolean overloaded = false;
            for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
                HandlerMethod other = e.getValue();
                if (!ClassUtils.getUserClass(other.getBeanType()).equals(controller)
                        || !other.getMethod().getName().equals(name)) {
                    continue;
                }
                if (other.getMethod().equals(handlerMethod.getMethod())) {
                    own.add(e.getKey());
                } else {
                    overloaded = true;
                }
            }
            if (overloaded && !own.isEmpty()) {
                id = disambiguate(id, own);
            }
        }
        operation.setOperationId(id);
        return operation;
    }

    /** {@code ProductController} + {@code create} gives {@code productCreate}. */
    static String baseId(Class<?> controller, String methodName) {
        String simple = controller.getSimpleName();
        if (simple.endsWith("Controller") && simple.length() > "Controller".length()) {
            simple = simple.substring(0, simple.length() - "Controller".length());
        }
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1) + capitalise(methodName);
    }

    private static String disambiguate(String base, List<RequestMappingInfo> infos) {
        TreeSet<String> suffixes = new TreeSet<>();
        for (RequestMappingInfo info : infos) {
            TreeSet<String> methods = new TreeSet<>();
            info.getMethodsCondition().getMethods().forEach(m -> methods.add(m.name()));
            TreeSet<String> paths = new TreeSet<>(info.getPathPatternsCondition() != null
                    ? info.getPathPatternsCondition().getPatternValues() : info.getPatternValues());
            suffixes.add(String.join("", methods.stream().map(StableOperationIds::capitaliseWord).toList())
                    + (paths.isEmpty() ? "" : pathSuffix(paths.first()))
                    + info.getConsumesCondition().getExpressions().stream()
                            .map(x -> capitaliseWord(x.getMediaType().getSubtype().replaceAll("[^A-Za-z0-9]", "")))
                            .sorted().reduce("", String::concat));
        }
        return base + suffixes.first();
    }

    /** {@code /api/v1/imports/{id}/rows.csv} gives {@code ApiV1ImportsByIdRowsCsv}. */
    static String pathSuffix(String path) {
        StringBuilder sb = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isBlank()) continue;
            if (segment.startsWith("{") && segment.endsWith("}")) {
                sb.append("By");
                segment = segment.substring(1, segment.length() - 1);
            }
            for (String word : segment.split("[^A-Za-z0-9]+")) {
                if (!word.isEmpty()) sb.append(capitaliseWord(word));
            }
        }
        return sb.toString();
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String capitaliseWord(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}
