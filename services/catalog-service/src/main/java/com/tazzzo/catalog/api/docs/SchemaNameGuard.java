package com.tazzzo.catalog.api.docs;

import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.Schema;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Documentation-only watchdog (OpenAPI contract correctness, M1). swagger-core names a component schema after the
 * class's simple name and a second class with the same simple name silently overwrites the first, so a route can
 * end up documented with another route's response shape. This converter never changes a schema: it only records which
 * {@code com.tazzzo} classes were published under which component name, so a test can fail on a collision.
 */
@Component
public class SchemaNameGuard implements ModelConverter {

    // static on purpose: swagger-core's converter registry is JVM-wide, so with several Spring contexts the first guard
    // registered sees every resolution; per-instance state would stay empty in the later contexts
    private static final Map<String, Set<String>> classesByName = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> primitiveProperties = new ConcurrentHashMap<>();

    @Override
    public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        Schema<?> resolved = chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
        Class<?> raw = rawClass(type.getType());
        if (resolved != null && raw != null && raw.getName().startsWith("com.tazzzo.")) {
            String name = resolved.get$ref() != null
                    ? resolved.get$ref().substring(resolved.get$ref().lastIndexOf('/') + 1)
                    : null;
            if (name != null) {
                classesByName.computeIfAbsent(name, n -> ConcurrentHashMap.newKeySet()).add(raw.getName());
                if (raw.isRecord()) {
                    Set<String> props = primitiveProperties.computeIfAbsent(name, n -> ConcurrentHashMap.newKeySet());
                    for (java.lang.reflect.RecordComponent c : raw.getRecordComponents()) {
                        if (c.getType().isPrimitive()) {
                            com.fasterxml.jackson.annotation.JsonProperty jp =
                                    c.getAccessor().getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
                            if (jp == null) jp = c.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
                            props.add(jp != null && !jp.value().isEmpty() ? jp.value() : c.getName());
                        }
                    }
                }
            }
        }
        return resolved;
    }

    /** Component name to the (more than one) classes that were published under it; empty when every name is unique. */
    public Map<String, Set<String>> collisions() {
        Map<String, Set<String>> out = new TreeMap<>();
        classesByName.forEach((name, classes) -> {
            if (classes.size() > 1) out.put(name, new TreeSet<>(classes));
        });
        return out;
    }

    /**
     * Component name to the JSON names of its record components whose Java type is a primitive. Such a value is always
     * present on the wire when the record is SERIALISED, so a response schema may list it as {@code required}. (When the
     * record is DESERIALISED, an absent primitive silently becomes 0/false, so request schemas must not.)
     */
    public Map<String, Set<String>> primitiveProperties() {
        return primitiveProperties;
    }

    /** Every published component name with its class(es), for diagnostics. */
    public Map<String, Set<String>> all() {
        Map<String, Set<String>> out = new TreeMap<>();
        classesByName.forEach((name, classes) -> out.put(name, new TreeSet<>(classes)));
        return out;
    }

    private static Class<?> rawClass(Type t) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof JavaType jt) return jt.getRawClass();
        if (t instanceof java.lang.reflect.ParameterizedType p && p.getRawType() instanceof Class<?> c) return c;
        return null;
    }
}
