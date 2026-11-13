package vn.greenmart.sales;

import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.data.redis.core.StringRedisTemplate;


@Configuration
public class TestRedisFallbackConfig {

    @Bean
    @Conditional(RedisExcludedCondition.class)
    public StringRedisTemplate testFallbackStringRedisTemplate() {
        return Mockito.mock(StringRedisTemplate.class, Mockito.RETURNS_DEEP_STUBS);
    }

    public static class RedisExcludedCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String excluded = context.getEnvironment().getProperty("spring.autoconfigure.exclude");
            return excluded != null && excluded.contains("RedisAutoConfiguration");
        }
    }
}
