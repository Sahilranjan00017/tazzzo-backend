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

    /** The pacing configuration is validated at construction: a nonsensical value refuses to start, never paces wrongly. */
    @Test void a_misconfigured_pacing_refuses_to_start() {
        for (String bad : new String[]{"tazzzo.scheduler.card-reconcile-ms=0", "tazzzo.scheduler.card-reconcile-full-pass-ms=0",
                "tazzzo.scheduler.card-reconcile-limit=0", "tazzzo.scheduler.card-reconcile-max-limit=10"}) {
            runner.withPropertyValues("tazzzo.scheduler.enabled=true", "tazzzo.scheduler.card-projection-enabled=true", bad)
                    .run(ctx -> assertThat(ctx).as(bad).hasFailed());
        }
        runner.withPropertyValues("tazzzo.scheduler.enabled=true", "tazzzo.scheduler.card-projection-enabled=true",
                        "tazzzo.scheduler.card-reconcile-limit=500", "tazzzo.scheduler.card-reconcile-max-limit=500")
                .run(ctx -> assertThat(ctx).as("floor == ceiling is a valid fixed pace").hasSingleBean(CommerceProjectionScheduler.class));
    }

    /** An existing floor above the new 20,000 default ceiling fails fast with a message naming both properties and env vars. */
    @Test void a_floor_above_the_default_ceiling_fails_naming_the_properties() {
        runner.withPropertyValues("tazzzo.scheduler.enabled=true", "tazzzo.scheduler.card-projection-enabled=true",
                        "tazzzo.scheduler.card-reconcile-limit=30000")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootMessage(ctx.getStartupFailure()))
                            .contains("tazzzo.scheduler.card-reconcile-limit", "TAZZZO_SCHEDULER_CARD_RECONCILE_LIMIT",
                                    "tazzzo.scheduler.card-reconcile-max-limit", "TAZZZO_SCHEDULER_CARD_RECONCILE_MAX_LIMIT",
                                    "=30000", "=20000");
                });
        runner.withPropertyValues("tazzzo.scheduler.enabled=true", "tazzzo.scheduler.card-projection-enabled=true",
                        "tazzzo.scheduler.card-reconcile-limit=30000", "tazzzo.scheduler.card-reconcile-max-limit=40000")
                .run(ctx -> assertThat(ctx).as("raising the max too is the documented fix").hasSingleBean(CommerceProjectionScheduler.class));
    }

    @Test void a_non_positive_reconcile_interval_names_its_property() {
        runner.withPropertyValues("tazzzo.scheduler.enabled=true", "tazzzo.scheduler.card-projection-enabled=true",
                        "tazzzo.scheduler.card-reconcile-ms=0")
                .run(ctx -> assertThat(rootMessage(ctx.getStartupFailure()))
                        .contains("tazzzo.scheduler.card-reconcile-ms", "TAZZZO_SCHEDULER_CARD_RECONCILE_MS", "milliseconds"));
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage();
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> iface) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(),
                new Class<?>[]{iface}, (proxy, method, args) -> null);
    }
}
