package com.vomatt.common.util;

import jakarta.servlet.http.HttpServletRequest;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Shared utility for resolving the real client IP.
 *
 * <p>This service runs behind a platform proxy (Zeabur); {@code X-Forwarded-For} is appended by a trusted edge.
 * XFF is honored only when the direct peer ({@code remoteAddr}) is a trusted proxy (loopback or private range),
 * otherwise a client reaching the app directly could spoof its IP. When trusted, takes the <strong>rightmost</strong>
 * non-empty IP: the client can forge the left segments freely, but cannot control the rightmost one appended by the edge.
 * Falls back to {@code remoteAddr} when there is no XFF or the peer is untrusted.</p>
 */
public final class ClientIpResolver {

    private ClientIpResolver() {}

    public static String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank() && isTrustedProxy(remoteAddr)) {
            String[] parts = forwarded.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String ip = parts[i].trim();
                if (!ip.isEmpty()) {
                    return ip;
                }
            }
        }
        return remoteAddr;
    }

    /**
     * Trusted proxies: loopback (127/8, ::1), RFC1918 (10/8, 172.16/12, 192.168/16) and IPv6 ULA (fd00::/8),
     * which covers Docker and Zeabur internal networks.
     */
    static boolean isTrustedProxy(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        // Only literal IPs are expected here; reject anything that looks like a hostname so no DNS lookup happens
        if (!remoteAddr.contains(":") && !remoteAddr.matches("[0-9.]+")) {
            return false;
        }
        try {
            InetAddress addr = InetAddress.getByName(remoteAddr);
            // isSiteLocalAddress is IPv4-only here: for IPv6 it means the deprecated fec0::/10, which is not trusted
            if (addr.isLoopbackAddress() || (addr instanceof Inet4Address && addr.isSiteLocalAddress())) {
                return true;
            }
            byte[] b = addr.getAddress();
            return b.length == 16 && (b[0] & 0xff) == 0xfd;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
