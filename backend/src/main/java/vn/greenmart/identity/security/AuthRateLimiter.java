package vn.greenmart.identity.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthRateLimiter {
    private final ObjectProvider<StringRedisTemplate> templates;

    public enum Bucket {
        LOGIN_IP("login:ip", 20, 60), REFRESH_IP("refresh:ip", 60, 60),
        PASSWORD_IP("password:ip", 20, 60), PASSWORD_USER("password:user", 5, 900);

        private final String scope;
        private final int attempts;
        private final int seconds;
        Bucket(String scope, int attempts, int seconds) {
            this.scope = scope; this.attempts = attempts; this.seconds = seconds;
        }
    }

    private static final DefaultRedisScript<Long> CHECK = new DefaultRedisScript<>("""
            local blocked = redis.call('PTTL', KEYS[2])
            if blocked >= 0 then return math.max(1, blocked) end
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local window = tonumber(ARGV[3]) * 1000
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then
                redis.call('SET', KEYS[2], '1', 'PX', window)
                redis.call('DEL', KEYS[1])
                return window
            end
            redis.call('ZADD', KEYS[1], now, ARGV[1])
            redis.call('PEXPIRE', KEYS[1], window)
            return 0
            """, Long.class);

    public long retryAfterSeconds(String clientIp) {
        return retryAfterSeconds(Bucket.LOGIN_IP, clientIp);
    }

    public long retryAfterSeconds(Bucket bucket, String identity) {
        StringRedisTemplate redis = templates.getIfAvailable();
        if (redis == null) throw new DataAccessResourceFailureException("Login limiter store unavailable");
        String key = "auth:" + bucket.scope + ":{" + hash(identity) + "}";
        Long remaining = redis.execute(CHECK, List.of(key + ":attempts", key + ":blocked"), UUID.randomUUID().toString(),
                Integer.toString(bucket.attempts), Integer.toString(bucket.seconds));
        if (remaining == null || remaining < 0) {
            throw new DataAccessResourceFailureException("Invalid login limiter decision");
        }
        return (remaining + 999) / 1000;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
