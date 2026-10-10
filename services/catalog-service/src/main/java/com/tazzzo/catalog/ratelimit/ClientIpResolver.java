package com.tazzzo.catalog.ratelimit;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the client IP for rate-limiting (CAT-SEC-1 Q5-IP-1 / Q5-IP-2a).
 *
 * <pre>
 *   direct TCP peer NOT in trusted_proxy_cidrs
 *       -> client_ip = remoteAddr, X-Forwarded-For IGNORED ENTIRELY
 *
 *   direct TCP peer IS trusted
 *       -> XFF is MANDATORY
 *       -> walk RIGHT to LEFT, skipping configured trusted-proxy addresses
 *       -> the first non-trusted LITERAL address is the client
 *
 *   malformed XFF · missing XFF from a trusted peer · a chain with no client address
 *       -> FAILS CLOSED  (ClientIpUnresolvableException)
 *       -> NEVER falls back to the proxy's own address
 * </pre>
 *
 * <p><b>Right-to-left, not "rightmost".</b> A single-proxy topology makes those the same thing, and
 * the ratified v1 path (client → ALB → service) is single-proxy today. They stop being the same the
 * moment a second trusted hop exists, and the abstraction has to survive that: the leftmost entries
 * are attacker-supplied, so the walk starts from the end the infrastructure wrote and stops at the
 * first address our infrastructure did not.
 *
 * <p><b>Nothing here parses hostnames.</b> A header value must never be able to trigger a DNS
 * lookup, so only literal addresses are accepted (see {@link CidrBlock#literalAddress}).
 */
public final class ClientIpResolver {

    private final List<CidrBlock> trustedProxies;

    public ClientIpResolver(List<String> trustedProxyCidrs) {
        List<CidrBlock> blocks = new ArrayList<>();
        for (String spec : trustedProxyCidrs) {
            if (spec != null && !spec.isBlank()) {
                blocks.add(CidrBlock.parse(spec));
            }
        }
        this.trustedProxies = List.copyOf(blocks);
    }

    /**
     * @param remoteAddr the direct TCP peer
     * @param forwardedFor the raw {@code X-Forwarded-For} header, or null when absent
     * @throws ClientIpUnresolvableException when a trusted peer yields no usable client address
     */
    public String resolve(String remoteAddr, String forwardedFor) {
        if (remoteAddr == null || CidrBlock.literalAddress(remoteAddr) == null) {
            throw new ClientIpUnresolvableException(
                    "direct peer address is absent or not a literal IP: " + remoteAddr);
        }
        if (!isTrustedProxy(remoteAddr)) {
            // Untrusted peer: whatever it claims in XFF is its own invention.
            return bucketKey(remoteAddr);
        }
        if (forwardedFor == null || forwardedFor.isBlank()) {
            throw new ClientIpUnresolvableException(
                    "trusted proxy " + remoteAddr + " sent no X-Forwarded-For");
        }
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (hop.isEmpty()) {
                throw new ClientIpUnresolvableException(
                        "malformed X-Forwarded-For from " + remoteAddr + ": empty element");
            }
            byte[] literal = CidrBlock.literalAddress(hop);
            if (literal == null) {
                throw new ClientIpUnresolvableException(
                        "malformed X-Forwarded-For from " + remoteAddr + ": not a literal address");
            }
            if (!isTrustedProxy(hop)) {
                return bucketKey(hop);
            }
        }
        // Every hop was one of our own proxies: the client address is simply not in the chain.
        throw new ClientIpUnresolvableException(
                "X-Forwarded-For from " + remoteAddr + " contains no client address");
    }

    public boolean isTrustedProxy(String address) {
        for (CidrBlock block : trustedProxies) {
            if (block.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The rate-limit identity of a client address (security review S-1). IPv4 (and IPv4-mapped IPv6, which
     * {@link CidrBlock#literalAddress} already yields as 4 bytes) keys by the dotted address. IPv6 keys by its /64
     * prefix, because one subscriber is routinely handed a whole /64 and would otherwise get unlimited fresh buckets
     * by rotating the host bits. Brackets and zone ids are dropped, so one client cannot occupy several buckets.
     */
    static String bucketKey(String address) {
        byte[] bytes = CidrBlock.literalAddress(address);
        if (bytes == null) {
            return address;   // unreachable: callers validate first; never throw from here
        }
        if (bytes.length == 4) {
            return (bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + "." + (bytes[2] & 0xFF) + "." + (bytes[3] & 0xFF);
        }
        return String.format("%x:%x:%x:%x::/64",
                ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF), ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF),
                ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF), ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF));
    }
}
