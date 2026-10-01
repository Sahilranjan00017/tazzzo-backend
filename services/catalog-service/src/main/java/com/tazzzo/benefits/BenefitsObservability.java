package com.tazzzo.benefits;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Bounded observability for the STANDALONE evaluation only. Tags are closed enums; never a customer, membership,
 * plan, plan version, subtotal, discount or exception text. A normal {@code NoBenefit} is not a failure and
 * records nothing. The transactional implementation has no access to this class (ArchUnit-enforced).
 */
@Component
public class BenefitsObservability {

    private static final Logger log = LoggerFactory.getLogger(BenefitsObservability.class);

    public enum Operation { EVALUATE }

    private final MeterRegistry registry;

    public BenefitsObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    public void failure(Operation operation, BenefitsFailure.Reason reason) {
        try {
            Counter.builder("benefits_failure")
                    .tag("operation", operation.name().toLowerCase(Locale.ROOT))
                    .tag("reason", reason.name().toLowerCase(Locale.ROOT)).register(registry).increment();
        } catch (RuntimeException e) {
            log.warn("benefits metric recording failed and was ignored: {}", e.getClass().getSimpleName());
        }
    }
}
