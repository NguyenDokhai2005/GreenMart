package vn.greenmart.integration;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.integration.channel.ChannelSignatures;
import vn.greenmart.inventory.PostgresTestImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"CHANNEL_WEB_SECRET=webhook-test-secret", "spring.rabbitmq.listener.simple.auto-startup=false"})
@AutoConfigureMockMvc
@Testcontainers
class ChannelWebhookTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChannelSignatures signatures;
    @Autowired ObjectMapper json;

    @Test void verifiesRawBodyAndReservesOnlyOnce() throws Exception {
        jdbc.update("INSERT INTO branch(id,code,name,address) VALUES (901,'W901','Demo','A')");
        jdbc.update("INSERT INTO category(id,name) VALUES (901,'Webhook')");
        jdbc.update("INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price) VALUES (205,'W205','Milk','bottle',901,29000,20000)");
        jdbc.update("INSERT INTO inventory(branch_id,product_id,on_hand,reserved) VALUES (901,205,30,0)");
        jdbc.update("UPDATE sales_channel SET default_branch_id=901 WHERE code='WEB'");
        jdbc.update("INSERT INTO channel_branch(channel_id,branch_id) SELECT id,901 FROM sales_channel WHERE code='WEB'");
        jdbc.update("INSERT INTO channel_product(channel_id,product_id,external_sku) SELECT id,205,'W-SUA-TH-1L' FROM sales_channel WHERE code='WEB'");
        var body = order("webhook-1", "W-SUA-TH-1L");
        var signature = signatures.sign("WEB", body);
        mvc.perform(post("/api/v1/integrations/WEB/orders").header("X-Signature", signature)
                .header("X-Correlation-ID", "webhook-invalid-signature")
                .content(order("webhook-1", "TAMPERED"))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_order WHERE external_order_id='webhook-1'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer WHERE phone='09123456789'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE trace_id='webhook-invalid-signature'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory WHERE branch_id=901 AND product_id=205", Integer.class)).isZero();
        mvc.perform(post("/api/v1/integrations/WEB/orders").header("X-Signature", signature).content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        for (int attempt = 0; attempt < 3; attempt++) {
            mvc.perform(post("/api/v1/integrations/WEB/orders").header("X-Signature", signature).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        }
        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory WHERE branch_id=901 AND product_id=205", Integer.class)).isEqualTo(3);
        var bad = order("webhook-2", "UNKNOWN");
        mvc.perform(post("/api/v1/integrations/WEB/orders").header("X-Signature", signatures.sign("WEB", bad)).content(bad))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNMAPPED_SKU"));
        assertThat(jdbc.queryForObject("SELECT status FROM sales_order WHERE external_order_id='webhook-2'", String.class)).isEqualTo("REJECTED");
        byte[] tooMany = new String(order("webhook-3", "W-SUA-TH-1L"), StandardCharsets.UTF_8)
                .replace("\"quantity\":3", "\"quantity\":30").getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/api/v1/integrations/WEB/orders").header("X-Signature", signatures.sign("WEB", tooMany)).content(tooMany))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        assertThat(jdbc.queryForObject("SELECT status FROM sales_order WHERE external_order_id='webhook-3'", String.class)).isEqualTo("REJECTED");
    }

    @Test void onlineOrderReservesStockForFifteenMinutes() throws Exception {
        jdbc.update("INSERT INTO branch(id,code,name,address) VALUES (902,'W902','Online demo','B')");
        jdbc.update("INSERT INTO category(id,name) VALUES (902,'Online webhook')");
        jdbc.update("INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price) VALUES (206,'W206','Rice','bag',902,29000,20000)");
        jdbc.update("INSERT INTO inventory(branch_id,product_id,on_hand,reserved) VALUES (902,206,30,0)");
        jdbc.update("INSERT INTO channel_branch(channel_id,branch_id) SELECT id,902 FROM sales_channel WHERE code='WEB'");
        jdbc.update("INSERT INTO channel_product(channel_id,product_id,external_sku) SELECT id,206,'W-RICE' FROM sales_channel WHERE code='WEB'");

        byte[] body = order("webhook-online", "W-RICE", "ONLINE", "09123456780");
        Instant before = Instant.now();
        byte[] response = mvc.perform(post("/api/v1/integrations/WEB/orders")
                .header("X-Signature", signatures.sign("WEB", body))
                .header("X-Correlation-ID", "webhook-online")
                .content(body)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andReturn().getResponse().getContentAsByteArray();
        Instant after = Instant.now();
        Instant expires = OffsetDateTime.parse(json.readTree(response).path("reservedUntil").asText()).toInstant();
        assertThat(expires).isBetween(before.plus(15, ChronoUnit.MINUTES), after.plus(15, ChronoUnit.MINUTES));
        assertThat(jdbc.queryForObject("SELECT reserved_until FROM sales_order WHERE external_order_id='webhook-online'",
                OffsetDateTime.class).toInstant()).isBetween(expires.minusNanos(1000), expires.plusNanos(1000));
        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory WHERE branch_id=902 AND product_id=206",
                Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type='stock.changed' AND trace_id='webhook-online'",
                Integer.class)).isEqualTo(1);
    }

    private byte[] order(String id, String sku) {
        return order(id, sku, "COD", "09123456789");
    }

    private byte[] order(String id, String sku, String paymentMethod, String phone) {
        return """
                {"externalOrderId":"%s","orderedAt":"2026-11-11T00:00:00Z",
                 "customer":{"name":"Demo","phone":"%s"},
                 "items":[{"externalSku":"%s","quantity":3,"unitPrice":29000}],"paymentMethod":"%s"}
                """.formatted(id, phone, sku, paymentMethod).getBytes(StandardCharsets.UTF_8);
    }
}
