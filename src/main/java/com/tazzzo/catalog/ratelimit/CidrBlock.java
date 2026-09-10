package com.tazzzo.catalog.ratelimit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * One trusted-proxy CIDR block, IPv4 or IPv6. Deliberately small: enough to answer "is this TCP
 * peer one of our proxies?", and nothing more.
 *
 * <p>Matching is done on the raw address bytes, so an IPv4 block never matches an IPv6 address and
 * vice versa — no implicit mapping, because a v4-mapped v6 address arriving from an unexpected
 * stack should read as untrusted rather than quietly trusted.
 */
public final class CidrBlock {

    private final byte[] network;
    private final int prefixBits;

    private CidrBlock(byte[] network, int prefixBits) {
        this.network = network;
        this.prefixBits = prefixBits;
    }

    /** Parses {@code 10.0.0.0/8}, {@code 2001:db8::/32}, or a bare address (treated as /32, /128). */
    public static CidrBlock parse(String spec) {
        String text = spec.trim();
        int slash = text.lastIndexOf('/');
        String addressPart = slash < 0 ? text : text.substring(0, slash);
        byte[] address = literalAddress(addressPart);
        if (address == null) {
            throw new IllegalArgumentException("not a valid CIDR or literal address: " + spec);
        }
        int bits = address.length * 8;
        int prefix = bits;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(text.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a valid CIDR prefix: " + spec);
            }
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("CIDR prefix out of range for the family: " + spec);
            }
        }
        return new CidrBlock(mask(address, prefix), prefix);
    }

    public boolean contains(String address) {
        byte[] candidate = literalAddress(address);
        if (candidate == null || candidate.length != network.length) {
            return false;
        }
        return Arrays.equals(mask(candidate, prefixBits), network);
    }

    /**
     * Parses a LITERAL address only. {@link InetAddress#getByName} would perform a DNS lookup for a
     * hostname; a header value must never be able to trigger one.
     */
    static byte[] literalAddress(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String candidate = text.trim();
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        int percent = candidate.indexOf('%');            // IPv6 zone id
        if (percent > 0) {
            candidate = candidate.substring(0, percent);
        }
        boolean looksNumeric = candidate.indexOf(':') >= 0
                || candidate.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '.');
        if (!looksNumeric) {
            return null;
        }
        try {
            return InetAddress.getByName(candidate).getAddress();
        } catch (UnknownHostException | SecurityException e) {
            return null;
        }
    }

    private static byte[] mask(byte[] address, int prefixBits) {
        byte[] out = address.clone();
        for (int i = 0; i < out.length; i++) {
            int remaining = prefixBits - i * 8;
            if (remaining >= 8) {
                continue;
            }
            out[i] = remaining <= 0 ? 0 : (byte) (out[i] & (0xFF << (8 - remaining)));
        }
        return out;
    }

    @Override
    public String toString() {
        try {
            return InetAddress.getByAddress(network).getHostAddress() + "/" + prefixBits;
        } catch (UnknownHostException e) {
            return "cidr/" + prefixBits;
        }
    }
}
