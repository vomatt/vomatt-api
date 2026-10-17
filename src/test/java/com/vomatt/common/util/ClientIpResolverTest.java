package com.vomatt.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

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
        for (String proxy : new String[] {"10.0.0.5", "172.20.1.1", "192.168.1.1", "127.0.0.1", "::1", "fd12::1"}) {
            assertThat(ClientIpResolver.resolve(request(proxy, "9.9.9.9, 198.51.100.2 , ")))
                    .as(proxy).isEqualTo("198.51.100.2");
        }
    }

    @Test
    void shouldNotTrustAddressesOutsidePrivateRanges() {
        assertThat(ClientIpResolver.isTrustedProxy("172.32.0.1")).isFalse();
        assertThat(ClientIpResolver.isTrustedProxy("2001:db8::1")).isFalse();
        assertThat(ClientIpResolver.isTrustedProxy("evil.example.com")).isFalse();
    }
}
