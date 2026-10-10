package com.tazzzo.notification;

import com.mongodb.client.MongoDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;

/**
 * The outbox is always wired (enqueue is part of the order transaction). The dispatcher runs only with BOTH
 * {@code tazzzo.scheduler.enabled} and {@code tazzzo.scheduler.notification-dispatch-enabled}, and refuses to start
 * against the disabled sender: there is no vendor adapter yet (external decision), so enabling dispatch with
 * {@code provider=disabled} is a startup failure, never a silent drop. {@code provider=sandbox} (dev/test environments
 * only) lets the full lifecycle run with no vendor.
 */
@Configuration
class NotificationConfig {

    @Bean
    NotificationOutbox notificationOutbox(MongoDatabase db, MeterRegistry registry) {
        return new NotificationOutbox(db, Clock.systemUTC(), registry);
    }

    @Bean
    NotificationMetrics notificationMetrics(MongoDatabase db, MeterRegistry registry,
                                            @Value("${tazzzo.notifications.metrics-refresh-seconds:15}") long refreshSeconds) {
        if (refreshSeconds < 1 || refreshSeconds > 3600) {
            throw new IllegalStateException("tazzzo.notifications.metrics-refresh-seconds must be 1..3600");
        }
        return new NotificationMetrics(db, Clock.systemUTC(), registry, Duration.ofSeconds(refreshSeconds));
    }

    /** {@code disabled} (default, delivers nothing) or the dev-only {@code sandbox}; see {@link NotificationProviderSelector}. */
    @Bean
    @ConditionalOnMissingBean(NotificationSender.class)
    NotificationSender notificationSender(@Value("${tazzzo.notifications.provider:disabled}") String provider,
                                          @Value("${tazzzo.migration.environment:}") String environment) {
        return NotificationProviderSelector.select(provider, environment);
    }

    @Configuration
    @ConditionalOnProperty(name = {"tazzzo.scheduler.enabled", "tazzzo.scheduler.notification-dispatch-enabled"},
            havingValue = "true")
    static class Dispatch {

        private final NotificationDispatcher dispatcher;
        private final int batch;

        Dispatch(NotificationOutbox outbox, NotificationSender sender, MeterRegistry registry,
                 @Value("${tazzzo.notifications.lease-seconds:60}") long leaseSeconds,
                 @Value("${tazzzo.notifications.max-age-seconds:3600}") long maxAgeSeconds,
                 @Value("${tazzzo.notifications.base-backoff-seconds:30}") long backoffSeconds,
                 @Value("${tazzzo.notifications.max-attempts:5}") int maxAttempts,
                 @Value("${tazzzo.notifications.batch-size:50}") int batch) {
            this.dispatcher = new NotificationDispatcher(outbox, sender, Clock.systemUTC(), registry,
                    Duration.ofSeconds(leaseSeconds), Duration.ofSeconds(maxAgeSeconds), Duration.ofSeconds(backoffSeconds),
                    maxAttempts);
            if (batch < 1 || batch > 500) {
                throw new IllegalStateException("tazzzo.notifications.batch-size must be 1..500");
            }
            this.batch = batch;
        }

        @Scheduled(fixedDelayString = "${tazzzo.notifications.dispatch-ms:5000}")
        void tick() {
            try {
                dispatcher.dispatchDue(batch);
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(NotificationDispatcher.class)
                        .warn("notification_dispatch_tick_failed error={}", e.getClass().getSimpleName());
            }
        }
    }
}
