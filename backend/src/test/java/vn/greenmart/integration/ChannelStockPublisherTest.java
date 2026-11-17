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
import vn.greenmart.integration.stocksync.ChannelStockPublisher;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ChannelStockPublisher.class, OutboxService.class, ChannelStockPublisherTest.Json.class})
@Testcontainers
class ChannelStockPublisherTest {
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
    @Autowired ChannelStockPublisher publisher;
    @Autowired ObjectMapper json;

    @Test void projectsAvailableStockAndDeduplicatesBrokerRedelivery() throws Exception {
        jdbc.update("INSERT INTO branch(id,code,name) VALUES (901,'D901','One'),(902,'D902','Two')");
        jdbc.update("INSERT INTO category(id,name) VALUES (901,'Demo')");
        jdbc.update("INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price) VALUES (205,'DEMO205','Milk','L',901,10,5)");
        jdbc.update("INSERT INTO inventory(branch_id,product_id,on_hand,reserved) VALUES (901,205,33,3),(902,205,13,0)");
        jdbc.update("UPDATE sales_channel SET safety_stock=2 WHERE code='WEB'");
        jdbc.update("INSERT INTO channel_branch(channel_id,branch_id) SELECT id,b FROM sales_channel CROSS JOIN (VALUES(901),(902)) AS branches(b) WHERE code='WEB'");
        jdbc.update("INSERT INTO channel_product(channel_id,product_id,external_sku) SELECT id,205,'W-DEMO' FROM sales_channel WHERE code='WEB'");
        publisher.onStockChanged(event("1", "{\"branchId\":901,\"productIds\":[205]}"));
        publisher.onStockChanged(event("1", "{\"branchId\":901,\"productIds\":[205]}"));
        var first = payload("1").path("body").path("items").get(0);
        assertThat(first.path("externalSku").asText()).isEqualTo("W-DEMO");
        assertThat(first.path("available").asLong()).isEqualTo(41);
        jdbc.update("UPDATE inventory SET on_hand=1,reserved=0 WHERE branch_id=901 AND product_id=205");
        jdbc.update("UPDATE inventory SET on_hand=0,reserved=0 WHERE branch_id=902 AND product_id=205");
        publisher.onStockChanged(event("2", "{\"branchId\":902,\"productId\":205}"));
        var next = payload("2").path("body").path("items").get(0);
        assertThat(next.path("available").asLong()).isZero();
        assertThat(next.path("version").asLong()).isGreaterThan(first.path("version").asLong());
    }

    private com.fasterxml.jackson.databind.JsonNode payload(String source) throws Exception {
        return json.readTree(jdbc.queryForObject("""
                SELECT payload::text FROM outbox_event WHERE event_type='channel.stock' AND payload->>'sourceEventId'=?
                """, String.class, source));
    }

    private Message event(String id, String raw) {
        var props = new MessageProperties();
        props.setMessageId(id);
        return new Message(raw.getBytes(StandardCharsets.UTF_8), props);
    }
}
