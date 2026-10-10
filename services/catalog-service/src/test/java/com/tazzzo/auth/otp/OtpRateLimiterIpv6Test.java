package com.tazzzo.auth.otp;

import com.tazzzo.catalog.ratelimit.Admission;
import com.tazzzo.catalog.ratelimit.BucketSpec;
import com.tazzzo.catalog.ratelimit.ClientIpResolver;
import com.tazzzo.catalog.ratelimit.RateLimitStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** S-1: an IPv6 client rotating host bits inside its /64 hits the burst limit instead of getting fresh buckets. */
class OtpRateLimiterIpv6Test {

    /** Fixed-capacity, non-refilling in-memory store: one counter per bucket key, all-or-nothing. */
    static final class CountingStore implements RateLimitStore {
        final Map<String, Long> used = new HashMap<>();

        @Override
        public Admission tryConsume(List<BucketSpec> buckets, int cost) {
            for (BucketSpec b : buckets) {
                if (used.getOrDefault(b.key(), 0L) + cost > b.capacity()) {
                    return new Admission.RateLimited(Duration.ofSeconds(1), List.of());
                }
            }
            buckets.forEach(b -> used.merge(b.key(), (long) cost, Long::sum));
            return new Admission.Allowed(List.of());
        }
    }

    private static OtpAuthProperties props() {
        OtpAuthProperties p = new OtpAuthProperties();
        p.setRequestIp(bucket(3));
        p.setRequestPhone(bucket(1000));
        p.setVerifyIp(bucket(3));
        p.setVerifyChallenge(bucket(1000));
        return p;
    }

    private static OtpAuthProperties.Bucket bucket(long capacity) {
        OtpAuthProperties.Bucket b = new OtpAuthProperties.Bucket();
        b.setCapacity(capacity);
        b.setRefillPerSecond(0.001);
        return b;
    }

    private final ClientIpResolver resolver = new ClientIpResolver(List.of());

    @Test
    void rotating_host_bits_in_one_slash_64_hits_the_request_burst_limit() {
        OtpRateLimiter limiter = new OtpRateLimiter(new CountingStore(), props());
        for (int i = 1; i <= 3; i++) {
            String ip = resolver.resolve("2001:db8:aa:bb:" + i + ":" + i + ":" + i + ":" + i, null);
            assertThat(limiter.admitRequest(ip, "phone" + i)).isInstanceOf(Admission.Allowed.class);
        }
        String fourth = resolver.resolve("2001:db8:aa:bb:ffff:eeee:dddd:cccc", null);
        assertThat(limiter.admitRequest(fourth, "phone4")).isInstanceOf(Admission.RateLimited.class);
        assertThat(limiter.admitVerify(fourth, "c1")).isInstanceOf(Admission.Allowed.class);
    }

    @Test
    void a_different_slash_64_has_its_own_budget() {
        OtpRateLimiter limiter = new OtpRateLimiter(new CountingStore(), props());
        for (int i = 1; i <= 3; i++) {
            limiter.admitRequest(resolver.resolve("2001:db8:aa:bb::" + i, null), "p" + i);
        }
        assertThat(limiter.admitRequest(resolver.resolve("2001:db8:aa:bc::1", null), "p9"))
                .isInstanceOf(Admission.Allowed.class);
    }

    @Test
    void ipv4_and_its_mapped_form_share_one_budget() {
        OtpRateLimiter limiter = new OtpRateLimiter(new CountingStore(), props());
        limiter.admitRequest(resolver.resolve("203.0.113.9", null), "p1");
        limiter.admitRequest(resolver.resolve("::ffff:203.0.113.9", null), "p2");
        limiter.admitRequest(resolver.resolve("203.0.113.9", null), "p3");
        assertThat(limiter.admitRequest(resolver.resolve("::ffff:203.0.113.9", null), "p4"))
                .isInstanceOf(Admission.RateLimited.class);
    }
}
