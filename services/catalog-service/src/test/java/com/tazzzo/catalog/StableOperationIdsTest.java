package com.tazzzo.catalog;

import com.tazzzo.catalog.api.StableOperationIds;
import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** The customizer is a pure function of the code: registration order must never change an id. */
class StableOperationIdsTest {

    @RestController
    static class WidgetController {
        @GetMapping("/w/{id}") public String get(String id) { return id; }
        @GetMapping("/w") public String list() { return ""; }
        @PostMapping("/w") public String create() { return ""; }
        @PostMapping(value = "/w/rows", consumes = "application/json") public String append() { return ""; }
        @PostMapping(value = "/w/rows", consumes = "text/csv") public String append(String csv) { return csv; }
        @GetMapping("/w/pinned") @Operation(operationId = "pinnedId") public String pinned() { return ""; }
    }

    @RestController
    static class GadgetController {
        @GetMapping("/g/{id}") public String get(String id) { return id; }
        @GetMapping("/g") public String list() { return ""; }
    }

    private Map<String, String> ids(boolean reversed, long seed) throws Exception {
        List<Method> methods = new ArrayList<>();
        for (Class<?> c : new Class<?>[]{WidgetController.class, GadgetController.class}) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.isSynthetic()) continue;
                methods.add(m);
            }
        }
        if (reversed) Collections.reverse(methods); else Collections.shuffle(methods, new java.util.Random(seed));
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(new org.springframework.web.context.support.StaticWebApplicationContext());
        mapping.afterPropertiesSet();
        Map<Method, Object> handlers = Map.of();
        for (Method m : methods) {
            Object bean = m.getDeclaringClass() == WidgetController.class ? new WidgetController() : new GadgetController();
            RequestMappingInfo info = RequestMappingInfo.paths(path(m))
                    .methods(m.isAnnotationPresent(PostMapping.class)
                            ? org.springframework.web.bind.annotation.RequestMethod.POST
                            : org.springframework.web.bind.annotation.RequestMethod.GET)
                    .consumes(consumes(m))
                    .build();
            mapping.registerMapping(info, bean, m);
        }
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("requestMappingHandlerMapping", mapping);
        StableOperationIds customizer = new StableOperationIds(bf.getBeanProvider(RequestMappingHandlerMapping.class));
        Map<String, String> out = new TreeMap<>();
        for (Method m : methods) {
            HandlerMethod hm = new HandlerMethod(m.getDeclaringClass() == WidgetController.class
                    ? new WidgetController() : new GadgetController(), m);
            io.swagger.v3.oas.models.Operation op = new io.swagger.v3.oas.models.Operation();
            op.setOperationId("springdoc_generated");
            out.put(m.getDeclaringClass().getSimpleName() + "#" + m.getName() + m.getParameterCount(),
                    customizer.customize(op, hm).getOperationId());
        }
        return out;
    }

    private static String[] consumes(Method m) {
        if (!m.getName().equals("append")) return new String[0];
        return new String[]{m.getParameterCount() == 1 ? "text/csv" : "application/json"};
    }

    private static String path(Method m) {
        if (m.isAnnotationPresent(PostMapping.class) && m.getName().equals("append")) return "/w/rows";
        if (m.getName().equals("get")) return m.getDeclaringClass() == WidgetController.class ? "/w/{id}" : "/g/{id}";
        if (m.getName().equals("pinned")) return "/w/pinned";
        return m.getDeclaringClass() == WidgetController.class ? "/w" : "/g";
    }

    @Test
    void ids_follow_the_scheme_and_keep_explicit_ids() throws Exception {
        Map<String, String> ids = ids(false, 1);
        assertThat(ids.get("WidgetController#get1")).isEqualTo("widgetGet");
        assertThat(ids.get("GadgetController#get1")).isEqualTo("gadgetGet");
        assertThat(ids.get("WidgetController#list0")).isEqualTo("widgetList");
        assertThat(ids.get("WidgetController#pinned0")).as("explicit id untouched").isEqualTo("springdoc_generated");
        assertThat(ids.get("WidgetController#append0")).isEqualTo("widgetAppendPostWRowsJson");
        assertThat(ids.get("WidgetController#append1")).isEqualTo("widgetAppendPostWRowsCsv");
    }

    @Test
    void ids_do_not_depend_on_registration_order() throws Exception {
        Map<String, String> reference = ids(true, 0);
        for (long seed = 0; seed < 25; seed++) {
            assertThat(ids(false, seed)).as("permutation " + seed).isEqualTo(reference);
        }
    }
}
