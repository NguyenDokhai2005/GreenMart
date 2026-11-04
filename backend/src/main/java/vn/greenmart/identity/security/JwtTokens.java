package vn.greenmart.identity.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
public class JwtTokens {
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Clock clock;
    public JwtTokens(@Value("${greenmart.jwt.secret}") String secret, Clock clock) {
        byte[] bytes=secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length<32 || secret.startsWith("change-me")) throw new IllegalStateException("JWT_SECRET phải có ít nhất 32 byte và không dùng placeholder");
        var key=new SecretKeySpec(bytes,"HmacSHA256");
        encoder=new NimbusJwtEncoder(new ImmutableSecret<>(key));
        var configured=NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        var timestamps=new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        configured.setJwtValidator(timestamps);
        decoder=configured;
        this.clock=clock;
    }
    public String issue(long id, String role) {
        Instant now=clock.instant();
        var claims=JwtClaimsSet.builder().id(java.util.UUID.randomUUID().toString())
                .subject(Long.toString(id)).issuedAt(now).expiresAt(now.plusSeconds(900))
                .claim("role",role).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
    public Jwt decode(String token) {
        Jwt jwt=decoder.decode(token);
        if (jwt.getExpiresAt()==null || jwt.getIssuedAt()==null || jwt.getSubject()==null
                || !jwt.getExpiresAt().isAfter(clock.instant())
                || jwt.getExpiresAt().isBefore(jwt.getIssuedAt()) || Long.parseLong(jwt.getSubject())<=0) {
            throw new JwtException("Invalid access token");
        }
        return jwt;
    }
}
