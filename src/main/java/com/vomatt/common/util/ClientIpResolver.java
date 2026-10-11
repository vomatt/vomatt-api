package com.vomatt.common.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Shared utility for resolving the real client IP.
 *
 * <p>This service runs behind a platform proxy (Zeabur); {@code X-Forwarded-For} is appended by a trusted edge.
 * Takes the <strong>rightmost</strong> non-empty IP: the client can forge the left segments freely, but cannot control the rightmost one appended by the edge.
 * Falls back to {@code remoteAddr} when there is no XFF.</p>
 *
 */
public final class ClientIpResolver {

    private ClientIpResolver() {}

    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String ip = parts[i].trim();
                if (!ip.isEmpty()) {
                    return ip;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
