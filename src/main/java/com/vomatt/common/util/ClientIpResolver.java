package com.vomatt.common.util;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

/**
 * Shared utility for resolving the real client IP.
 *
 * <p>vomatt-web calls this API from its own servers, so the peer and {@code X-Forwarded-For} name vomatt-web, not the
 * user. It passes the user's IP in {@link #CLIENT_IP_HEADER}, honored only with the shared secret
 * ({@code app.security.client-ip-secret}) in {@link #CLIENT_IP_SECRET_HEADER}; anyone else could set the header to dodge
 * rate limits and OTP lockouts, or to lock out someone else's IP.</p>
 *
 * <p>Otherwise this service runs behind a platform proxy; {@code X-Forwarded-For} is appended by a trusted edge.
 * XFF is honored only when the direct peer ({@code remoteAddr}) is inside a trusted proxy CIDR
 * ({@code app.security.trusted-proxies}), otherwise a client reaching the app directly could spoof its IP. When trusted,
 * walks XFF from the <strong>right</strong>, skipping entries that are themselves trusted proxies (e.g. a CDN hop in front
 * of the ingress), and takes the first other one: the client can forge the left segments freely, but not the ones our
 * proxies appended. Falls back to {@code remoteAddr} when there is no XFF or the peer is untrusted.</p>
 */
public final class ClientIpResolver {

    public static final String CLIENT_IP_HEADER = "X-Client-IP";
    public static final String CLIENT_IP_SECRET_HEADER = "X-Client-IP-Secret";

    /**
     * Loopback, RFC1918, IPv6 ULA (Docker and Zeabur internal networks) and CGNAT 100.64.0.0/10,
     * which platform ingresses may connect from.
     */
    public static final String DEFAULT_TRUSTED_PROXIES =
            "127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,fd00::/8,100.64.0.0/10";

    private record Cidr(byte[] network, int prefix) {
        boolean contains(byte[] addr) {
            if (addr.length != network.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addr[i] != network[i]) {
                    return false;
                }
            }
            int restBits = prefix % 8;
            if (restBits == 0) {
                return true;
            }
            int mask = 0xff << (8 - restBits);
            return (addr[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    private static final java.util.regex.Pattern IPV4 =
            java.util.regex.Pattern.compile("((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
    // Hex digits, colons and dots only (embedded IPv4 allowed): InetAddress then parses it as a literal, never via DNS
    private static final java.util.regex.Pattern IPV6_CHARS = java.util.regex.Pattern.compile("[0-9a-fA-F:.]*:[0-9a-fA-F:.]*");

    private static volatile List<Cidr> trustedProxies = parse(DEFAULT_TRUSTED_PROXIES);

    // Null when unset: the client IP header is then ignored
    private static volatile byte[] clientIpSecret;

    private ClientIpResolver() {}

    /** Sets the secret that lets a caller name the client IP; blank turns that off. */
    public static void setClientIpSecret(String secret) {
        clientIpSecret = secret == null || secret.isBlank() ? null : secret.getBytes(StandardCharsets.UTF_8);
    }

    /** Replaces the trusted proxy list (comma-separated CIDRs); blank keeps the default. Throws on a malformed CIDR. */
    public static void setTrustedProxies(String cidrs) {
        trustedProxies = parse(cidrs == null || cidrs.isBlank() ? DEFAULT_TRUSTED_PROXIES : cidrs);
    }

    public static String resolve(HttpServletRequest request) {
        String forwardedClient = trustedClientIp(request);
        if (forwardedClient != null) {
            return forwardedClient;
        }
        String remoteAddr = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank() && isTrustedProxy(remoteAddr)) {
            String[] parts = forwarded.split(",");
            String leftmost = null;
            for (int i = parts.length - 1; i >= 0; i--) {
                String ip = parts[i].trim();
                if (ip.isEmpty()) {
                    continue;
                }
                if (!isTrustedProxy(ip)) {
                    return ip;
                }
                leftmost = ip;
            }
            // Every hop is a trusted proxy: the leftmost is the closest thing to a client address
            if (leftmost != null) {
                return leftmost;
            }
        }
        return remoteAddr;
    }

    /** The IP vomatt-web named for its user, or null when the secret is unset, absent or wrong, or the IP isn't a literal. */
    private static String trustedClientIp(HttpServletRequest request) {
        byte[] secret = clientIpSecret;
        String sent = request.getHeader(CLIENT_IP_SECRET_HEADER);
        String ip = request.getHeader(CLIENT_IP_HEADER);
        if (secret == null || sent == null || ip == null
                || !MessageDigest.isEqual(secret, sent.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        String trimmed = ip.trim();
        return literalAddress(trimmed) != null ? trimmed : null;
    }

    static boolean isTrustedProxy(String remoteAddr) {
        byte[] addr = literalAddress(remoteAddr);
        return addr != null && trustedProxies.stream().anyMatch(c -> c.contains(addr));
    }

    private static List<Cidr> parse(String cidrs) {
        return Arrays.stream(cidrs.split(",")).map(String::trim).filter(c -> !c.isEmpty()).map(c -> {
            String[] parts = c.split("/", 2);
            byte[] network = literalAddress(parts[0]);
            if (network == null) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + c);
            }
            int max = network.length * 8;
            int prefix;
            try {
                prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : max;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + c, e);
            }
            if (prefix < 0 || prefix > max) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + c);
            }
            return new Cidr(network, prefix);
        }).toList();
    }

    /**
     * Parses an IP literal; returns null for anything else. Pre-filtered so InetAddress never falls back to a DNS
     * lookup (it would for "999.1.1.1" or "zz:1") and never accepts shorthand like "1.2.3".
     */
    private static byte[] literalAddress(String ip) {
        if (ip == null || !(IPV4.matcher(ip).matches() || IPV6_CHARS.matcher(ip).matches())) {
            return null;
        }
        try {
            return InetAddress.getByName(ip).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
