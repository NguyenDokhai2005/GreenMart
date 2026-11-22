package vn.greenmart.identity.security;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AuthRateLimiterTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.6-alpine").withExposedPorts(6379);
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private AuthRateLimiter limiter;
    private AuthRateLimiter secondInstance;

    @BeforeEach
    void setup() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        try (var connection = connectionFactory.getConnection()) { connection.serverCommands().flushDb(); }
        var provider = new StaticListableBeanFactory(Map.of("redis", redis)).getBeanProvider(StringRedisTemplate.class);
        limiter = new AuthRateLimiter(provider);
        secondInstance = new AuthRateLimiter(provider);
    }

    @AfterEach void close() { connectionFactory.destroy(); }

    @Test
    void twentyAllowedThenSharedCooldownWithoutExtendingIt() {
        for (int attempt = 0; attempt < 20; attempt++) assertThat(limiter.retryAfterSeconds("192.0.2.1")).isZero();
        assertThat(secondInstance.retryAfterSeconds("192.0.2.1")).isEqualTo(60);
        String blocked = key("blocked");
        assertThat(redis.keys("*:attempts")).isEmpty();
        redis.expire(blocked, Duration.ofSeconds(3));
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(limiter.retryAfterSeconds("192.0.2.1")).isBetween(1L, 3L);
        }
        assertThat(redis.getExpire(blocked, TimeUnit.MILLISECONDS)).isBetween(1L, 3000L);
        redis.expireAt(blocked, Instant.EPOCH);
        assertThat(secondInstance.retryAfterSeconds("192.0.2.1")).isZero();
        assertThat(redis.opsForZSet().zCard(key("attempts"))).isEqualTo(1);
        assertThat(redis.getExpire(key("attempts"))).isBetween(1L, 60L);
    }

    @Test
    void rollingWindowExpiresOnlyOldAttempts() {
        for (int attempt = 0; attempt < 20; attempt++) limiter.retryAfterSeconds("192.0.2.1");
        String attempts = key("attempts");
        var members = redis.opsForZSet().range(attempts, 0, -1).stream().toList();
        Long now = redis.execute(new DefaultRedisScript<>("""
                local t = redis.call('TIME')
                return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
                """, Long.class), java.util.List.of());
        for (String member : members.subList(0, 6)) redis.opsForZSet().add(attempts, member, now - 60000);
        for (int attempt = 0; attempt < 6; attempt++) assertThat(limiter.retryAfterSeconds("192.0.2.1")).isZero();
        assertThat(limiter.retryAfterSeconds("192.0.2.1")).isEqualTo(60);
    }

    @ParameterizedTest
    @EnumSource(AuthRateLimiter.Bucket.class)
    void concurrentInstancesRespectEachBucket(AuthRateLimiter.Bucket bucket) throws Exception {
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(24)) {
            var pending = new ArrayList<Future<Long>>();
            for (int index = 0; index < 100; index++) {
                var instance = index % 2 == 0 ? limiter : secondInstance;
                pending.add(workers.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                    return instance.retryAfterSeconds(bucket, "192.0.2.1");
                }));
            }
            start.countDown();
            var results = new ArrayList<Long>();
            for (var future : pending) results.add(future.get(20, TimeUnit.SECONDS));
            int expected = switch (bucket) {
                case LOGIN_IP, PASSWORD_IP -> 20;
                case REFRESH_IP -> 60;
                case PASSWORD_USER -> 5;
            };
            assertThat(results.stream().filter(value -> value == 0).count()).isEqualTo(expected);
            assertThat(results.stream().filter(value -> value > 0).count()).isEqualTo(100 - expected);
        }
    }

    @Test
    void differentIpsHaveIndependentLimits() {
        for (int attempt = 0; attempt < 21; attempt++) limiter.retryAfterSeconds("192.0.2.1");
        assertThat(limiter.retryAfterSeconds("192.0.2.2")).isZero();
        assertThat(redis.keys("auth:login:ip:*")).allMatch(key -> !key.contains("192.0.2."));
    }

    @Test
    void scopesAreIndependentAndPasswordUserCooldownDoesNotExtend() {
        for (int index = 0; index < 21; index++) limiter.retryAfterSeconds("192.0.2.1");
        assertThat(limiter.retryAfterSeconds(AuthRateLimiter.Bucket.REFRESH_IP, "192.0.2.1")).isZero();
        for (int index = 0; index < 5; index++) {
            assertThat(limiter.retryAfterSeconds(AuthRateLimiter.Bucket.PASSWORD_USER, "7")).isZero();
        }
        assertThat(secondInstance.retryAfterSeconds(AuthRateLimiter.Bucket.PASSWORD_USER, "7")).isEqualTo(900);
        String blocked = redis.keys("auth:password:user:*:blocked").iterator().next();
        redis.expire(blocked, Duration.ofSeconds(3));
        assertThat(limiter.retryAfterSeconds(AuthRateLimiter.Bucket.PASSWORD_USER, "7")).isBetween(1L, 3L);
        assertThat(redis.getExpire(blocked, TimeUnit.MILLISECONDS)).isBetween(1L, 3000L);
    }

    private String key(String suffix) {
        var keys = redis.keys("auth:login:ip:*:" + suffix);
        assertThat(keys).hasSize(1);
        return keys.iterator().next();
    }
}
