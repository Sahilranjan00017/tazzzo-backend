package com.tazzzo.catalog;

import com.tazzzo.catalog.ratelimit.ClientIpResolver;
import com.tazzzo.catalog.ratelimit.ClientIpUnresolvableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Q5-IP-1 / Q5-IP-2a. Pure: no Spring, no HTTP, no Redis.
 *
 * <p>The property under test is not "we read a header" — it is that a header CANNOT be used to
 * choose one's own rate-limit bucket. Every spoofing shape below is an attempt to do exactly that.
 */
class ClientIpResolverTest {

    /** The ALB, as a deployment would configure it. */
    private static final List<String> TRUSTED = List.of("10.0.0.0/8", "2001:db8::/32");

    private final ClientIpResolver resolver = new ClientIpResolver(TRUSTED);
    private final ClientIpResolver trustNobody = new ClientIpResolver(List.of());

    // ---------- untrusted peer: the header is its own invention ----------

    @Test
    void an_untrusted_peer_cannot_choose_its_bucket_with_a_header() {
        assertThat(resolver.resolve("203.0.113.9", "1.2.3.4"))
                .as("a direct client's XFF is ignored entirely")
                .isEqualTo("203.0.113.9");
        assertThat(resolver.resolve("203.0.113.9", "1.2.3.4, 5.6.7.8, 9.9.9.9"))
                .isEqualTo("203.0.113.9");
        assertThat(resolver.resolve("203.0.113.9", "not-an-ip-at-all"))
                .as("a malformed header from an untrusted peer is not even parsed")
                .isEqualTo("203.0.113.9");
        assertThat(trustNobody.resolve("10.0.0.5", "1.2.3.4"))
                .as("with no configured proxy, even an RFC1918 peer is untrusted")
                .isEqualTo("10.0.0.5");
    }

    // ---------- trusted proxy: right-to-left past our own hops ----------

    @Test
    void a_trusted_proxy_yields_the_address_it_appended() {
        // ALB append mode: the client's own XFF is on the left, the ALB's observation on the right.
        assertThat(resolver.resolve("10.0.1.7", "198.51.100.23"))
                .isEqualTo("198.51.100.23");
        assertThat(resolver.resolve("10.0.1.7", "spoofed-by-client, 198.51.100.23"))
                .as("the attacker-supplied left element must not win")
                .isEqualTo("198.51.100.23");
    }

    @Test
    void the_walk_skips_our_own_proxies_and_stops_at_the_first_that_is_not_ours() {
        assertThat(resolver.resolve("10.0.1.7", "198.51.100.23, 10.0.2.9"))
                .as("a second trusted hop is skipped, not returned")
                .isEqualTo("198.51.100.23");
        assertThat(resolver.resolve("10.0.1.7", "203.0.113.5, 10.0.2.9, 10.0.3.4"))
                .isEqualTo("203.0.113.5");
    }

    /**
     * The spoof that a plain "rightmost" rule survives and a "leftmost" rule does not: the client
     * plants an address that LOOKS like one of ours so the walk keeps going past its real address.
     */
    @Test
    void a_client_planting_a_trusted_looking_address_still_resolves_to_itself() {
        assertThat(resolver.resolve("10.0.1.7", "10.9.9.9, 198.51.100.23"))
                .as("the ALB-appended real address is to the right of the plant")
                .isEqualTo("198.51.100.23");
    }

    @Test
    void ipv6_is_handled_on_both_sides() {
        assertThat(resolver.resolve("2001:db8::1", "2606:4700::1111"))
                .isEqualTo("2606:4700::1111");
        assertThat(resolver.resolve("[2001:db8::1]", "[2606:4700::1111]"))
                .as("bracketed forms normalise")
                .isEqualTo("2606:4700::1111");
        assertThat(resolver.resolve("2001:db8::1", "2606:4700::1111%eth0"))
                .as("a zone id must not split one client across two buckets")
                .isEqualTo("2606:4700::1111");
        assertThat(resolver.resolve("2001:dead::1", "1.2.3.4"))
                .as("an IPv6 peer outside the trusted block is untrusted")
                .isEqualTo("2001:dead::1");
    }

    @Test
    void an_ipv4_block_never_trusts_an_ipv6_peer_or_the_reverse() {
        ClientIpResolver v4Only = new ClientIpResolver(List.of("10.0.0.0/8"));
        assertThat(v4Only.isTrustedProxy("2001:db8::1")).isFalse();
        ClientIpResolver v6Only = new ClientIpResolver(List.of("2001:db8::/32"));
        assertThat(v6Only.isTrustedProxy("10.0.0.1")).isFalse();
    }

    // ---------- fail closed, never fall back to the proxy ----------

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not-an-ip", "1.2.3.4, example.com", "999.999.999.999",
            "1.2.3.4, ", "1.2.3.4, not-an-ip"})
    void a_trusted_proxy_with_an_unusable_chain_fails_closed(String forwardedFor) {
        assertThatThrownBy(() -> resolver.resolve("10.0.1.7", forwardedFor))
                .as("never fall back to the ALB address: that would be one bucket for everyone")
                .isInstanceOf(ClientIpUnresolvableException.class);
    }

    /**
     * Garbage to the LEFT of the resolved client is ignored, and that is deliberate.
     *
     * <p>Everything left of the address our infrastructure appended is attacker-supplied by
     * construction — a client may put any bytes it likes in {@code X-Forwarded-For} and the ALB
     * simply appends after them. Demanding that region be well-formed would let a caller force its
     * own request to 503 by sending nonsense, while buying nothing: the walk stops at the first
     * non-trusted address from the right, which is the one our proxy actually observed.
     *
     * <p>What must still fail closed is malformed content in the part we DO walk — the tests above.
     */
    @Test
    void attacker_supplied_garbage_left_of_the_resolved_client_is_ignored() {
        assertThat(resolver.resolve("10.0.1.7", "1.2.3.4, , 5.6.7.8")).isEqualTo("5.6.7.8");
        assertThat(resolver.resolve("10.0.1.7", "<script>, 198.51.100.23")).isEqualTo("198.51.100.23");
        assertThat(resolver.resolve("10.0.1.7", ", , 198.51.100.23")).isEqualTo("198.51.100.23");
    }

    @Test
    void a_trusted_proxy_sending_no_header_at_all_fails_closed() {
        assertThatThrownBy(() -> resolver.resolve("10.0.1.7", null))
                .isInstanceOf(ClientIpUnresolvableException.class)
                .hasMessageContaining("no X-Forwarded-For");
    }

    @Test
    void a_chain_of_only_our_own_proxies_fails_closed() {
        assertThatThrownBy(() -> resolver.resolve("10.0.1.7", "10.0.2.9, 10.0.3.4"))
                .as("the client address is simply not present")
                .isInstanceOf(ClientIpUnresolvableException.class)
                .hasMessageContaining("no client address");
    }

    @Test
    void an_absent_or_non_literal_peer_address_fails_closed() {
        assertThatThrownBy(() -> resolver.resolve(null, "1.2.3.4"))
                .isInstanceOf(ClientIpUnresolvableException.class);
        assertThatThrownBy(() -> resolver.resolve("some-hostname", "1.2.3.4"))
                .as("a hostname must never trigger a DNS lookup from a request path")
                .isInstanceOf(ClientIpUnresolvableException.class);
    }

    @Test
    void a_malformed_trusted_cidr_fails_at_construction_not_on_the_first_request() {
        assertThatThrownBy(() -> new ClientIpResolver(List.of("10.0.0.0/99")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientIpResolver(List.of("not-a-cidr")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
