package vn.greenmart.identity;

import org.junit.jupiter.api.Test;
import vn.greenmart.identity.security.JwtTokens;
import java.time.Clock;
import static org.assertj.core.api.Assertions.*;

class JwtTokensTest {
    @Test void rejectsMissingStrengthOrExampleSecrets() {
        assertThatThrownBy(()->new JwtTokens("short",Clock.systemUTC())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new JwtTokens("change-me-to-a-long-random-string",Clock.systemUTC())).isInstanceOf(IllegalStateException.class);
    }
    @Test void generatedTokenHasExactly15MinuteLifetimeAndHs256Algorithm() {
        var tokens=new JwtTokens("test-only-secret-with-at-least-32-bytes",Clock.systemUTC());
        var jwt=tokens.decode(tokens.issue(7,"CASHIER"));
        assertThat(jwt.getExpiresAt().getEpochSecond()-jwt.getIssuedAt().getEpochSecond()).isEqualTo(900);
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(jwt.getSubject()).isEqualTo("7");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("CASHIER");
    }
    @Test void tokensIssuedInSameSecondAreDistinct() {
        var clock=Clock.fixed(java.time.Instant.parse("2026-11-04T00:00:00Z"),java.time.ZoneOffset.UTC);
        var tokens=new JwtTokens("test-only-secret-with-at-least-32-bytes",clock);
        String first=tokens.issue(7,"CASHIER"), second=tokens.issue(7,"CASHIER");
        assertThat(second).isNotEqualTo(first);
        assertThat(tokens.decode(second).getId()).isNotEqualTo(tokens.decode(first).getId());
    }
    @Test void tokenIsExpiredExactlyAtExpiryTime() {
        String secret="test-only-secret-with-at-least-32-bytes";
        var start=java.time.Instant.parse("2026-11-04T00:00:00Z");
        var issuer=new JwtTokens(secret,Clock.fixed(start,java.time.ZoneOffset.UTC));
        String token=issuer.issue(7,"CASHIER");
        var atExpiry=new JwtTokens(secret,Clock.fixed(start.plusSeconds(900),java.time.ZoneOffset.UTC));
        assertThatThrownBy(()->atExpiry.decode(token)).isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }
}
