package vn.greenmart.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.integration.outbox.OutboxService;

import static org.assertj.core.api.Assertions.assertThat;
import vn.greenmart.integration.orderstatus.ChannelOrderStatusPublisher;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ChannelOrderStatusPublisher.class, OutboxService.class, ChannelOrderStatusPublisherTest.Json.class})
@Testcontainers
class ChannelOrderStatusPublisherTest {
    @TestConfiguration static class Json {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ChannelOrderStatusPublisher publisher;
    @Autowired ObjectMapper json;

    @Test void forwardsFiveStatusesAndIgnoresPosAndRedelivery() throws Exception {
        jdbc.update("INSERT INTO branch(id,code,name) VALUES (901,'D901','One')");
        jdbc.update("""
                INSERT INTO sales_order(id,code,channel_id,external_order_id,branch_id,subtotal,total)
                SELECT 901,'WEB-901',id,'external-901',901,10,10 FROM sales_channel WHERE code='WEB'
                """);
        jdbc.update("""
                INSERT INTO sales_order(id,code,channel_id,branch_id,subtotal,total)
                SELECT 902,'POS-902',id,901,10,10 FROM sales_channel WHERE code='POS'
                """);
        var states = java.util.List.of("CONFIRMED","COMPLETED","CANCELLED","EXPIRED","REJECTED");
        for (int i = 0; i < states.size(); i++) {
            String status = states.get(i);
            var event = event(Integer.toString(i + 1), status, 901);
            publisher.onOrderStatus(event);
            publisher.onOrderStatus(event);
            var payload = json.readTree(jdbc.queryForObject("""
                    SELECT payload::text FROM outbox_event WHERE event_type='channel.order.status' AND payload->>'sourceEventId'=?
                    """, String.class, Integer.toString(i + 1)));
            assertThat(payload.path("externalId").asText()).isEqualTo("external-901");
            assertThat(payload.path("channelCode").asText()).isEqualTo("WEB");
            assertThat(payload.path("body").path("status").asText()).isEqualTo(status);
            assertThat(payload.path("body").path("occurredAt").asText()).isEqualTo("2026-11-11T00:00:00Z");
            if ("REJECTED".equals(status)) {
                assertThat(payload.path("body").path("reason").asText()).isEqualTo("UNMAPPED_SKU");
            }
        }
        publisher.onOrderStatus(event("9", "COMPLETED", 902));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type='channel.order.status'",
                Integer.class)).isEqualTo(5);
    }

    private Message event(String source, String status, int order) {
        var props = new MessageProperties();
        props.setMessageId(source);
        props.setReceivedRoutingKey("order." + status.toLowerCase(java.util.Locale.ROOT));
        String reason = "REJECTED".equals(status) ? ",\"reason\":\"UNMAPPED_SKU\"" : "";
        return new Message(("{\"orderId\":" + order + ",\"occurredAt\":\"2026-11-11T00:00:00Z\"" + reason + "}")
                .getBytes(StandardCharsets.UTF_8), props);
    }
}
