package vn.greenmart.identity.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;

/** Current refresh hashes index live sessions; predecessor lookups allow racing logout to revoke descendants. */
@Repository
@RequiredArgsConstructor
public class RefreshTokenStore {
    private final ObjectProvider<StringRedisTemplate> templates;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private static final long TTL = 604800;
    public record Session(String id, String token) {}

    private static final DefaultRedisScript<Long> ROTATE = new DefaultRedisScript<>("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            if ARGV[2] ~= '' then
                if redis.call('GET', KEYS[2]) ~= ARGV[2] or not redis.call('ZSCORE', KEYS[1], ARGV[2]) then return 0 end
                redis.call('ZREM', KEYS[1], ARGV[2])
            elseif redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
            redis.call('SET', KEYS[2], ARGV[4], 'EX', ARGV[5])
            redis.call('SET', KEYS[3], ARGV[6], 'EX', ARGV[5])
            redis.call('ZADD', KEYS[1], ARGV[3], ARGV[4])
            redis.call('EXPIRE', KEYS[1], ARGV[5])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> ACTIVE = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[2])
            if not current or (ARGV[2] ~= '' and current ~= ARGV[2]) then return 0 end
            local expires = redis.call('ZSCORE', KEYS[1], current)
            if not expires or tonumber(expires) <= tonumber(ARGV[1]) then return 0 end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REVOKE = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[2])
            if current then redis.call('ZREM', KEYS[1], current) end
            redis.call('DEL', KEYS[2])
            return 1
            """, Long.class);

    private String key(long userId) { return "auth:refresh:{" + userId + "}"; }
    private String sessionKey(long userId, String sessionId) { return key(userId) + ":session:" + sessionId; }
    private String lookupKey(long userId, String tokenHash) { return key(userId) + ":lookup:" + tokenHash; }

    public long userId(String token) {
        try {
            if (token == null || !token.matches("[1-9][0-9]{0,18}\\.[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException();
            return Long.parseLong(token.substring(0, token.indexOf('.')));
        } catch (RuntimeException exception) { throw new AppException(ErrorCode.INVALID_REFRESH_TOKEN); }
    }
    private String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private <T> T available(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DataAccessException exception) { throw new AppException(ErrorCode.AUTH_UNAVAILABLE); }
    }
    private StringRedisTemplate redis() {
        var redis = templates.getIfAvailable();
        if (redis == null) throw new AppException(ErrorCode.AUTH_UNAVAILABLE);
        return redis;
    }

    /** Reject forged/expired refresh credentials before any account lookup or row lock. */
    public long verify(String token) {
        long userId = userId(token);
        return available(() -> {
            String tokenHash = hash(token);
            String sessionId = redis().opsForValue().get(lookupKey(userId, tokenHash));
            if (sessionId == null || !active(userId, sessionId, tokenHash)) throw new AppException(ErrorCode.INVALID_REFRESH_TOKEN);
            return userId;
        });
    }
    public boolean isSessionActive(long userId, String sessionId) {
        return available(() -> active(userId, sessionId, ""));
    }
    private boolean active(long userId, String sessionId, String expectedHash) {
        Long result = redis().execute(ACTIVE, List.of(key(userId), sessionKey(userId, sessionId)),
                Long.toString(clock.millis()), expectedHash);
        if (result == null) throw new AppException(ErrorCode.AUTH_UNAVAILABLE);
        return Long.valueOf(1).equals(result);
    }
    public Session issue(long userId) { return rotate(userId, null); }
    public Session rotate(long userId, String previous) {
        return available(() -> {
            String previousHash = previous == null ? "" : hash(previous);
            String sessionId = previous == null ? UUID.randomUUID().toString()
                    : redis().opsForValue().get(lookupKey(userId, previousHash));
            if (sessionId == null) throw new AppException(ErrorCode.INVALID_REFRESH_TOKEN);
            byte[] entropy = new byte[32];
            random.nextBytes(entropy);
            String token = userId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
            String tokenHash = hash(token);
            long now = clock.millis();
            Long result = redis().execute(ROTATE, List.of(key(userId), sessionKey(userId, sessionId), lookupKey(userId, tokenHash)),
                    Long.toString(now), previousHash, Long.toString(now + TTL * 1000), tokenHash, Long.toString(TTL), sessionId);
            if (!Long.valueOf(1).equals(result)) throw new AppException(ErrorCode.INVALID_REFRESH_TOKEN);
            return new Session(sessionId, token);
        });
    }
    /** Possession of a refresh secret permits revocation only, including after rotation, expiry or account lockout. */
    public void revoke(String token) {
        long userId = userId(token);
        available(() -> {
            String tokenHash = hash(token);
            String sessionId = redis().opsForValue().get(lookupKey(userId, tokenHash));
            if (sessionId == null) redis().opsForZSet().remove(key(userId), tokenHash);
            else if (!Long.valueOf(1).equals(redis().execute(REVOKE, List.of(key(userId), sessionKey(userId, sessionId))))) {
                throw new AppException(ErrorCode.AUTH_UNAVAILABLE);
            }
            return null;
        });
    }
    public void revokeAll(long userId) {
        available(() -> redis().delete(key(userId)));
    }
}
