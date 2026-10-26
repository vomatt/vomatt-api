package com.vomatt.common.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientIpResolverTest {

    private static final String SECRET = "client-ip-secret-0123456789abcdef";

    @AfterEach
    void restoreDefault() {
        ClientIpResolver.setTrustedProxies(null);
        ClientIpResolver.setClientIpSecret(null);
    }

    private static MockHttpServletRequest fromWeb(String clientIp, String secret) {
        MockHttpServletRequest req = request("203.0.113.7", null);
        req.addHeader(ClientIpResolver.CLIENT_IP_HEADER, clientIp);
        req.addHeader(ClientIpResolver.CLIENT_IP_SECRET_HEADER, secret);
        return req;
    }

    @Test
    void shouldUseClientIpHeaderWhenSecretMatches() {
        ClientIpResolver.setClientIpSecret(SECRET);
        assertThat(ClientIpResolver.resolve(fromWeb(" 198.51.100.9 ", SECRET))).isEqualTo("198.51.100.9");
        assertThat(ClientIpResolver.resolve(fromWeb("2001:db8::1", SECRET))).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    void shouldNormalizeClientIpWhenSpelledDifferently() {
        ClientIpResolver.setClientIpSecret(SECRET);
        String canonical = ClientIpResolver.resolve(fromWeb("2001:db8:0:0:0:0:0:1", SECRET));
        assertThat(ClientIpResolver.resolve(fromWeb("2001:DB8::1", SECRET))).isEqualTo(canonical);
        assertThat(ClientIpResolver.resolve(fromWeb("2001:0db8::0001", SECRET))).isEqualTo(canonical);
        assertThat(ClientIpResolver.resolve(fromWeb("::ffff:198.51.100.9", SECRET))).isEqualTo("198.51.100.9");
    }

    @Test
    void shouldAcceptClientIpWhenConfiguredSecretHasSurroundingWhitespace() {
        // An env value ending in a newline; the container trims the header value
        ClientIpResolver.setClientIpSecret(" " + SECRET + "\n");
        assertThat(ClientIpResolver.resolve(fromWeb("198.51.100.9", SECRET))).isEqualTo("198.51.100.9");
    }

    @Test
    void shouldIgnoreClientIpHeaderWhenSecretIsWrong() {
        ClientIpResolver.setClientIpSecret(SECRET);
        assertThat(ClientIpResolver.resolve(fromWeb("198.51.100.9", SECRET + "x"))).isEqualTo("203.0.113.7");
    }

    @Test
    void shouldIgnoreClientIpHeaderWhenNoSecretIsConfigured() {
        assertThat(ClientIpResolver.resolve(fromWeb("198.51.100.9", ""))).isEqualTo("203.0.113.7");
        assertThat(ClientIpResolver.resolve(fromWeb("198.51.100.9", SECRET))).isEqualTo("203.0.113.7");
    }

    @Test
    void shouldIgnoreClientIpHeaderWhenValueIsNotAnIpLiteral() {
        ClientIpResolver.setClientIpSecret(SECRET);
        assertThat(ClientIpResolver.resolve(fromWeb("example.com", SECRET))).isEqualTo("203.0.113.7");
        assertThat(ClientIpResolver.resolve(fromWeb("1.2.3.4, 5.6.7.8", SECRET))).isEqualTo("203.0.113.7");
    }

    private static MockHttpServletRequest request(String remoteAddr, String xff) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(remoteAddr);
        if (xff != null) {
            req.addHeader("X-Forwarded-For", xff);
        }
        return req;
    }

    @Test
    void shouldIgnoreForwardedForWhenRemoteAddrIsPublic() {
        assertThat(ClientIpResolver.resolve(request("203.0.113.7", "1.2.3.4"))).isEqualTo("203.0.113.7");
    }

    @Test
    void shouldUseRightmostForwardedForWhenRemoteAddrIsTrustedProxy() {
        for (String proxy : new String[] {"10.0.0.5", "172.20.1.1", "192.168.1.1", "127.0.0.1", "::1", "fd12::1", "100.64.0.1", "100.127.255.254"}) {
            assertThat(ClientIpResolver.resolve(request(proxy, "9.9.9.9, 198.51.100.2 , ")))
                    .as(proxy).isEqualTo("198.51.100.2");
        }
    }

    @Test
    void shouldSkipTrustedHopsWhenForwardedForHasSeveralProxies() {
        assertThat(ClientIpResolver.resolve(request("10.0.0.5", "6.6.6.6, 198.51.100.2, 10.1.2.3, 100.64.0.9")))
                .isEqualTo("198.51.100.2");
    }

    @Test
    void shouldUseLeftmostWhenEveryForwardedHopIsTrusted() {
        assertThat(ClientIpResolver.resolve(request("10.0.0.5", "192.168.1.4, 10.1.2.3"))).isEqualTo("192.168.1.4");
    }

    @Test
    void shouldNotTrustAddressesOutsidePrivateRanges() {
        assertThat(ClientIpResolver.isTrustedProxy("172.32.0.1")).isFalse();
        assertThat(ClientIpResolver.isTrustedProxy("2001:db8::1")).isFalse();
        assertThat(ClientIpResolver.isTrustedProxy("fec0::1")).isFalse();
        assertThat(ClientIpResolver.isTrustedProxy("evil.example.com")).isFalse();
        // Not literals: must be rejected without a DNS lookup, and shorthand must not be expanded
        for (String bad : new String[] {"zz:1", "g::1", "999.1.1.1", "10.1", "10.0.0.256"}) {
            assertThat(ClientIpResolver.isTrustedProxy(bad)).as(bad).isFalse();
        }
        assertThat(ClientIpResolver.isTrustedProxy("100.128.0.1")).isFalse();
        assertThat(ClientIpResolver.isTrustedProxy("100.63.255.255")).isFalse();
    }

    @Test
    void shouldTrustOnlyConfiguredCidrsWhenTrustedProxiesSet() {
        ClientIpResolver.setTrustedProxies("203.0.113.0/24, 2001:db8::/32");

        assertThat(ClientIpResolver.isTrustedProxy("203.0.113.7")).isTrue();
        assertThat(ClientIpResolver.isTrustedProxy("2001:db8::1")).isTrue();
        assertThat(ClientIpResolver.isTrustedProxy("10.0.0.5")).isFalse();
        assertThat(ClientIpResolver.resolve(request("203.0.113.7", "198.51.100.2"))).isEqualTo("198.51.100.2");
    }

    @Test
    void shouldKeepDefaultWhenTrustedProxiesBlank() {
        ClientIpResolver.setTrustedProxies(" ");

        assertThat(ClientIpResolver.isTrustedProxy("100.64.0.1")).isTrue();
    }

    @Test
    void shouldRejectMalformedCidrWhenTrustedProxiesSet() {
        for (String bad : new String[] {"10.0.0.0/33", "proxy.example.com/8", "10.0.0.0/x", "::/129"}) {
            assertThatThrownBy(() -> ClientIpResolver.setTrustedProxies(bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
