package vn.greenmart.common.logging;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = RabbitCorrelationTest.Config.class, properties = {
    "spring.main.web-application-type=none", "logging.structured.format.console=logstash",
    "management.endpoint.health.group.readiness.include=readinessState", "spring.data.redis.password=test"
})
@Testcontainers
@ActiveProfiles("correlation-test")
@ExtendWith(OutputCaptureExtension.class)
class RabbitCorrelationTest {
    @Container static GenericContainer<?> rabbit = new GenericContainer<>("rabbitmq:3.13.7-management-alpine")
            .withExposedPorts(5672).withEnv("RABBITMQ_DEFAULT_USER", "test").withEnv("RABBITMQ_DEFAULT_PASS", "test");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.host", rabbit::getHost);
        r.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        r.add("spring.rabbitmq.username", () -> "test"); r.add("spring.rabbitmq.password", () -> "test");
    }
    @Autowired RabbitTemplate template;
    @Autowired Receiver receiver;

    @Test void httpTraceTravelsToConsumerAndJsonLogWithoutLeakingToNextMessage(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest(); request.addHeader(TraceContext.HEADER, "trace-http-rabbit-129");
        new CorrelationFilter().doFilter(request, new MockHttpServletResponse(), (req, res) ->
                template.convertAndSend("trace.test", "first"));
        template.convertAndSend("trace.test", "second");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(receiver.traces.get("first")).isEqualTo("trace-http-rabbit-129");
            assertThat(receiver.traces.get("second")).isNotBlank().isNotEqualTo("trace-http-rabbit-129");
            assertThat(output.getOut()).contains("\"traceId\":\"trace-http-rabbit-129\"");
        });
        assertThat(MDC.get("traceId")).isNull();
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("correlation-test")
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import(RabbitCorrelationConfiguration.class)
    static class Config {
        @Bean Queue traceQueue() { return new Queue("trace.test", false); }
        @Bean Receiver receiver() { return new Receiver(); }
    }
    static class Receiver {
        final ConcurrentHashMap<String, String> traces = new ConcurrentHashMap<>();
        @RabbitListener(queues = "trace.test") public void receive(String body) { traces.put(body, MDC.get("traceId")); }
    }
}
