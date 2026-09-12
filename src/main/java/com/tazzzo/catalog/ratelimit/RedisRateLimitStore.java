package com.tazzzo.catalog.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The distributed weighted token bucket (Q5-MECH-1), evaluated entirely inside one Redis/Valkey
 * script so that every catalogue instance consumes from the SAME logical state.
 *
 * <p><b>Why a script and not read-modify-write.</b> Two tasks admitting concurrently would each read
 * the same token count and each debit it, so the effective allowance would silently double with the
 * replica count — the very thing Q5-MECH-1 rejects. The script makes check-and-debit atomic, and it
 * spans ALL buckets in one call so Q5-ATOMIC-1's all-or-nothing rule holds across them too.
 *
 * <p><b>Why Redis server time and not the JVM clock.</b> Refill is elapsed-time arithmetic. If each
 * instance supplied its own clock, drift between tasks would become drift in the allowance, and a
 * task whose clock ran fast would mint tokens. {@code redis.call('TIME')} gives one clock for
 * everyone.
 *
 * <p><b>No fallback.</b> A connection failure returns {@link Admission.Unavailable}; it never
 * degrades to per-instance limiting and never fails open. The same verdict covers a cost that
 * exceeds a bucket's capacity — an impossible request is an invariant fault, not throttling.
 */
public class RedisRateLimitStore implements RateLimitStore {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitStore.class);

    /**
     * ARGV: [1] cost, [2] bucket count, then per bucket i: capacity at 1+2i, refill at 2+2i.
     * Returns {allowed, retryAfterMillis, remaining_1 … remaining_n}.
     *
     * Two passes on purpose: the first only READS and computes, so a deficiency in the last bucket
     * still leaves the earlier ones undebited (Q5-ATOMIC-1).
     *
     * The per-bucket remaining values are the OBSERVATION Q5-OBS-1 needs, returned from the SAME
     * atomic execution that made the decision: post-debit on ALLOWED, the undebited available
     * count on RATE_LIMITED. A separate Redis read afterwards would describe a bucket another
     * request may already have changed.
     *
     * They are returned as DECIMAL STRINGS, not numbers: Redis converts a Lua number reply to an
     * integer and truncates, so 2.9 tokens would report as 2 and saturation would read
     * systematically higher than the state that produced the decision. Since these metrics drive
     * later production tuning, that approximation must not be embedded. The decision itself always
     * used the fractional value; now the observation matches it.
     */
    private static final String TOKEN_BUCKET_LUA = """
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local cost = tonumber(ARGV[1])
            local n = tonumber(ARGV[2])
            local available = {}
            local deficient = false
            local waitMs = 0
            for i = 1, n do
              local cap = tonumber(ARGV[1 + 2 * i])
              local rate = tonumber(ARGV[2 + 2 * i])
              local state = redis.call('HMGET', KEYS[i], 'tokens', 'ts')
              local tokens = tonumber(state[1])
              local ts = tonumber(state[2])
              if tokens == nil or ts == nil then
                tokens = cap
                ts = now
              end
              local elapsed = now - ts
              if elapsed < 0 then elapsed = 0 end
              tokens = math.min(cap, tokens + (elapsed / 1000.0) * rate)
              available[i] = tokens
              if tokens < cost then
                deficient = true
                local w = math.ceil(((cost - tokens) / rate) * 1000)
                if w > waitMs then waitMs = w end
              end
            end
            if deficient then
              local reply = {0, waitMs}
              for i = 1, n do
                reply[#reply + 1] = string.format('%.14g', available[i])
              end
              return reply
            end
            local reply = {1, 0}
            for i = 1, n do
              local cap = tonumber(ARGV[1 + 2 * i])
              local rate = tonumber(ARGV[2 + 2 * i])
              local left = available[i] - cost
              redis.call('HSET', KEYS[i], 'tokens', left, 'ts', now)
              redis.call('EXPIRE', KEYS[i], math.ceil(cap / rate) + 60)
              reply[#reply + 1] = string.format('%.14g', left)
            end
            return reply
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> script;

    public RedisRateLimitStore(StringRedisTemplate redis) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>(TOKEN_BUCKET_LUA, List.class);
    }

    /**
     * Pairs the script's trailing remaining-values with the buckets that produced them. Only the
     * DIMENSION and the numbers leave this class — never the key, which embeds the client identity.
     */
    private static List<BucketObservation> observations(List<BucketSpec> buckets, List<?> result) {
        List<BucketObservation> out = new ArrayList<>(buckets.size());
        for (int i = 0; i < buckets.size(); i++) {
            int at = 2 + i;
            double remaining = Double.NaN;
            if (at < result.size()) {
                Object raw = result.get(at);
                if (raw instanceof Number n) {
                    remaining = n.doubleValue();
                } else if (raw instanceof String str) {
                    try {
                        remaining = Double.parseDouble(str);
                    } catch (NumberFormatException ignored) {
                        remaining = Double.NaN;
                    }
                } else if (raw instanceof byte[] bytes) {
                    try {
                        remaining = Double.parseDouble(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                    } catch (NumberFormatException ignored) {
                        remaining = Double.NaN;
                    }
                }
            }
            if (Double.isNaN(remaining)) {
                // The script always returns n values; a short reply is a contract violation and
                // must not be papered over with a plausible number.
                throw new IllegalStateException("limiter script returned no remaining for bucket " + i);
            }
            BucketSpec spec = buckets.get(i);
            out.add(new BucketObservation(spec.dimension(), spec.capacity(), remaining));
        }
        return List.copyOf(out);
    }

    @Override
    public Admission tryConsume(List<BucketSpec> buckets, int cost) {
        if (buckets == null || buckets.isEmpty()) {
            // Never silently allow: no bucket means the caller failed to build an identity.
            return new Admission.Unavailable("no buckets supplied");
        }
        if (cost <= 0) {
            throw new IllegalArgumentException("cost must be positive, was " + cost);
        }
        for (BucketSpec bucket : buckets) {
            if (cost > bucket.capacity()) {
                // An IMPOSSIBLE request, not a throttled one: a full bucket only ever reaches
                // capacity, so no amount of waiting makes this admissible. Returning
                // RATE_LIMITED with a finite Retry-After would be a lie the client obeys forever.
                // This is a configuration/invariant fault, and it is answered as one — checked
                // BEFORE any Redis call, so nothing is debited.
                return new Admission.Unavailable("cost " + cost + " exceeds bucket capacity "
                        + bucket.capacity() + "; no wait can admit it");
            }
        }
        List<String> keys = new ArrayList<>(buckets.size());
        List<String> args = new ArrayList<>(2 + buckets.size() * 2);
        args.add(Integer.toString(cost));
        args.add(Integer.toString(buckets.size()));
        for (BucketSpec bucket : buckets) {
            keys.add(bucket.key());
            args.add(Long.toString(bucket.capacity()));
            args.add(Double.toString(bucket.refillPerSecond()));
        }
        try {
            List<?> result = redis.execute(script, keys, args.toArray());
            if (result == null || result.size() < 2) {
                return new Admission.Unavailable("limiter script returned no verdict");
            }
            long allowed = ((Number) result.get(0)).longValue();
            long retryAfterMillis = ((Number) result.get(1)).longValue();
            List<BucketObservation> observations = observations(buckets, result);
            return allowed == 1
                    ? new Admission.Allowed(observations)
                    : new Admission.RateLimited(Duration.ofMillis(Math.max(retryAfterMillis, 1)),
                            observations);
        } catch (RuntimeException e) {
            // Deliberately broad: any store failure is UNAVAILABLE, never an allow and never a 429.
            log.warn("consumer rate-limit store unavailable: {}", e.toString());
            return new Admission.Unavailable(e.getClass().getSimpleName());
        }
    }
}
