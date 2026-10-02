package com.vomatt.common.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 解析真實 client IP 的共用 utility。
 *
 * <p>本服務部署在平台代理（Zeabur）後，{@code X-Forwarded-For} 由可信 edge 附加。
 * 取<strong>最右側</strong>非空 IP——client 可任意偽造左側段落，但無法控制 edge 附加在最右的那一段。
 * 無 XFF 時退回 {@code remoteAddr}。</p>
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
