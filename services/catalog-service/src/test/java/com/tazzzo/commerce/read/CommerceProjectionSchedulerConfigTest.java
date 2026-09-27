package com.tazzzo.commerce.read;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.repo.FreshnessObservability;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-10A final gate: the projection scheduler activates ONLY when BOTH the master
 * {@code tazzzo.scheduler.enabled} and the subordinate {@code tazzzo.scheduler.card-projection-enabled}
 * are true — the master remains the global kill switch. Verifies actual bean presence/absence via
 * {@link ApplicationContextRunner}, not annotation string inspection.
 *
 * <p>The Mongo beans are JDK dynamic-proxy stubs: {@link CommerceProjectionScheduler}'s constructor
 * only STORES the client/db (it makes no driver call at construction), so a no-op proxy is enough
 * and no mocking framework is required.
 */
class CommerceProjectionSchedulerConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(MongoClient.class, () -> stub(MongoClient.class))
            .withBean(MongoDatabase.class, () -> stub(MongoDatabase.class))
            .withBean(FreshnessObservability.class, () -> new FreshnessObservability(new SimpleMeterRegistry()))
            .withUserConfiguration(CommerceProjectionScheduler.class);

    @Test void off_when_master_off_and_card_off() {
        runner.withPropertyValues("tazzzo.scheduler.enabled=false",
                        "tazzzo.scheduler.card-projection-enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(CommerceProjectionScheduler.class));
    }

    @Test void off_when_master_off_and_card_on() {
        runner.withPropertyValues("tazzzo.scheduler.enabled=false",
                        "tazzzo.scheduler.card-projection-enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(CommerceProjectionScheduler.class));
    }

    @Test void off_when_master_on_and_card_off() {
        runner.withPropertyValues("tazzzo.scheduler.enabled=true",
                        "tazzzo.scheduler.card-projection-enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(CommerceProjectionScheduler.class));
    }

    @Test void on_only_when_master_on_and_card_on() {
        runner.withPropertyValues("tazzzo.scheduler.enabled=true",
                        "tazzzo.scheduler.card-projection-enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(CommerceProjectionScheduler.class));
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> iface) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(),
                new Class<?>[]{iface}, (proxy, method, args) -> null);
    }
}
