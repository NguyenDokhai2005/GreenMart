package vn.greenmart.identity.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/** One sorted set per user: only SHA-256 hashes, each score is its own expiry. */
@Repository
@RequiredArgsConstructor
public class RefreshTokenStore {
    private final org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> templates;
    private StringRedisTemplate redis() { return templates.getObject(); }
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    private static final long TTL=604800;
    private static final DefaultRedisScript<Long> ROTATE=new DefaultRedisScript<>("""
            redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',ARGV[1])
            if ARGV[2] ~= '' then
              if not redis.call('ZSCORE',KEYS[1],ARGV[2]) then return 0 end
              redis.call('ZREM',KEYS[1],ARGV[2])
            end
            redis.call('ZADD',KEYS[1],ARGV[3],ARGV[4])
            redis.call('EXPIRE',KEYS[1],ARGV[5])
            return 1
            """,Long.class);
    private String key(long userId) { return "auth:refresh:{"+userId+"}"; }
    public long userId(String token) {
        try {
            if (!token.matches("[1-9][0-9]{0,18}\\.[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException();
            return Long.parseLong(token.substring(0,token.indexOf('.')));
        } catch (RuntimeException ex) { throw new AppException(ErrorCode.INVALID_REFRESH_TOKEN); }
    }
    private String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public String issue(long userId) { return rotate(userId,null); }
    public String rotate(long userId, String previous) {
        byte[] entropy=new byte[32]; random.nextBytes(entropy);
        String token=userId+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        long now=clock.millis();
        Long result=redis().execute(ROTATE,List.of(key(userId)),Long.toString(now),previous==null?"":hash(previous),
                Long.toString(now+TTL*1000),hash(token),Long.toString(TTL));
        if (!Long.valueOf(1).equals(result)) throw new AppException(ErrorCode.INVALID_REFRESH_TOKEN);
        return token;
    }
    public void revoke(long userId, String token) { redis().opsForZSet().remove(key(userId),hash(token)); }
    public void revokeAll(long userId) { redis().delete(key(userId)); }
}
