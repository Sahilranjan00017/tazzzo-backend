package com.tazzzo.commerce.read;

import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.repo.ProjectionRebuildQueue;
import com.tazzzo.catalog.repo.WritePath;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Production wiring of the projection freshness source hooks (PR-10A review, HIGH #4). When
 * {@code tazzzo.freshness.enabled=true}, this provides the shared {@link ProjectionRebuildQueue}
 * bean and ATTACHES it to the singleton {@link WritePath} bean — so every real production Catalog
 * product mutation (which all funnel through WritePath) enqueues a rebuild in the same transaction.
 * This makes the already-active Catalog source hook LIVE in production, not merely test-wired.
 *
 * <p>With the flag off (the default, and every test profile), no queue bean exists and WritePath's
 * hook stays null — behavior is entirely unchanged, so the existing suite is unaffected. The
 * attachment is a deliberate, explicit, one-time startup step expressed as a bean dependency (not
 * a hidden mutation buried elsewhere), and it is covered by a Spring-context wiring test.
 *
 * <p><b>Pricing/Media wiring (PR-10B final review #3):</b> {@code PricingService}/{@code MediaService}
 * ARE production Spring beans as of PR-10B ({@code CommerceReadConfig}), constructed there via an
 * {@code ObjectProvider<ProjectionRebuildQueue>} so they receive THIS bean when freshness is enabled
 * and fall back to the pre-PR-10A no-queue constructor when it is not — never a second queue, never
 * a duplicate bean. The drift reconciler remains the correctness backstop for any write path that
 * still predates this wiring.
 */
@Configuration
@ConditionalOnProperty(value = "tazzzo.freshness.enabled", havingValue = "true")
public class CommerceFreshnessConfig {

    @Bean
    public ProjectionRebuildQueue projectionRebuildQueue(MongoDatabase db) {
        return new ProjectionRebuildQueue(db, Clock.systemUTC());
    }

    @Bean
    public FreshnessWritePathWiring freshnessWritePathWiring(WritePath writePath,
                                                            ProjectionRebuildQueue queue) {
        writePath.setRebuildQueue(queue);
        return new FreshnessWritePathWiring();
    }

    /** Marker bean: makes the WritePath attachment an explicit, ordered context dependency. */
    public static final class FreshnessWritePathWiring { }
}
