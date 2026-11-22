package vn.greenmart.identity.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class LoginClientIpResolverTest {
    @Test
    void untrustedClientCannotChooseItsBucketWithForwardingHeaders() {
        var request = request("192.0.2.10", "203.0.113.1");
        request.addHeader("X-Forwarded-For", "203.0.113.2, 127.0.0.1");
        assertThat(new LoginClientIpResolver("127.0.0.1/32").resolve(request)).isEqualTo("192.0.2.10");
    }

    @Test
    void trustedNetworksAndConfiguredHostnamesUseOverwrittenRealIp() {
        var request = request("127.0.0.1", "203.0.113.1");
        assertThat(new LoginClientIpResolver("127.0.0.1/32").resolve(request)).isEqualTo("203.0.113.1");
        assertThat(new LoginClientIpResolver("localhost").resolve(request)).isEqualTo("203.0.113.1");
    }

    @Test
    void invalidOrMultipleAddressesFromTrustedProxyFallBackToPeer() {
        var resolver = new LoginClientIpResolver("127.0.0.1");
        for (String value : new String[]{"example.com", "203.0.113.1, 198.51.100.1", "999.1.1.1", ""}) {
            assertThat(resolver.resolve(request("127.0.0.1", value))).isEqualTo("127.0.0.1");
        }
    }

    @Test
    void equivalentIpv6AndIpv4MappedAddressesShareCanonicalIdentity() {
        var resolver = new LoginClientIpResolver("");
        assertThat(resolver.resolve(request("2001:db8::1", "")))
                .isEqualTo(resolver.resolve(request("2001:0db8:0:0:0:0:0:1", "")));
        assertThat(resolver.resolve(request("::ffff:192.0.2.1", ""))).isEqualTo("192.0.2.1");
        assertThat(resolver.resolve(request("not-an-ip", "203.0.113.1"))).isEqualTo("unknown");
    }

    private MockHttpServletRequest request(String peer, String realIp) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        request.addHeader("X-Real-IP", realIp);
        return request;
    }
}
