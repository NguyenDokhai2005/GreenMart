package vn.greenmart.sales;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import vn.greenmart.sales.domain.PaymentMethod;
import vn.greenmart.sales.domain.PaymentSimulation;
import vn.greenmart.sales.dto.OrderLineRequest;
import vn.greenmart.sales.dto.PaymentRequest;
import vn.greenmart.sales.dto.PosOrderRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("PosIdempotencyApiTest - Testcontainers Concurrency & Idempotency (Issue #34)")
class PosIdempotencyApiTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("greenmart_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.placeholders.forecast_user", () -> "forecast_ro");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.autoconfigure.exclude", () -> "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedData() {
        jdbcTemplate.execute("DELETE FROM order_item;");
        jdbcTemplate.execute("DELETE FROM sales_order;");
        jdbcTemplate.execute("DELETE FROM inventory_transaction;");
        jdbcTemplate.execute("DELETE FROM outbox_event;");

        jdbcTemplate.execute("""
            INSERT INTO branch (id, code, name, is_active) VALUES (1, 'GM-001', 'GreenMart Q1', true)
            ON CONFLICT (id) DO NOTHING;
            INSERT INTO sales_channel (id, code, name, default_branch_id, safety_stock)
            VALUES (1, 'POS', 'Point of Sale', 1, 0)
            ON CONFLICT (id) DO NOTHING;
            INSERT INTO category (id, name) VALUES (1, 'Thực phẩm') ON CONFLICT (id) DO NOTHING;
            INSERT INTO product (id, sku, barcode, name, unit, category_id, sale_price, cost_price, is_active)
            VALUES (101, 'SKU-101', 'BAR-101', 'Gạo ST25', 'túi', 1, 100000, 80000, true)
            ON CONFLICT (id) DO NOTHING;
            INSERT INTO app_user (id, username, password_hash, full_name, role, is_active)
            VALUES (1, 'thungan.gm001', '$2a$10$abcdef', 'Thu Ngân 1', 'CASHIER', true)
            ON CONFLICT (id) DO NOTHING;
            INSERT INTO user_branch (user_id, branch_id) VALUES (1, 1) ON CONFLICT DO NOTHING;
            INSERT INTO inventory (branch_id, product_id, on_hand, reserved, version, updated_at)
            VALUES (1, 101, 50, 0, 1, NOW())
            ON CONFLICT (branch_id, product_id) DO UPDATE SET on_hand = 50, reserved = 0;
        """);
    }

    @Test
    @DisplayName("AC3: 2 Request song song cùng Idempotency-Key -> 1 đơn 201, 1 đơn 409/200, DB chỉ trừ 1 lần")
    void testConcurrentOrdersWithSameIdempotencyKey() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();

        PosOrderRequest request = new PosOrderRequest(
                1L, null,
                List.of(new OrderLineRequest(101L, 2)),
                null,
                new PaymentRequest(PaymentMethod.CASH, 200000L, PaymentSimulation.SUCCESS),
                200000L
        );
        String requestBody = objectMapper.writeValueAsString(request);

        int numberOfThreads = 2;
        CyclicBarrier barrier = new CyclicBarrier(numberOfThreads);
        List<Future<Integer>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads)) {
            for (int i = 0; i < numberOfThreads; i++) {
                futures.add(executor.submit(() -> {
                    barrier.await();
                    MvcResult result = mockMvc.perform(post("/api/v1/orders/pos")
                                    .with(user("1").roles("CASHIER"))
                                    .header("Idempotency-Key", idempotencyKey)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestBody))
                            .andReturn();
                    return result.getResponse().getStatus();
                }));
            }

            List<Integer> statusCodes = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statusCodes.add(f.get(15, TimeUnit.SECONDS));
            }

            assertThat(statusCodes).contains(201);
            int otherStatus = statusCodes.get(0) == 201 ? statusCodes.get(1) : statusCodes.get(0);
            assertThat(otherStatus).isIn(409, 200);
        }

        Integer orderCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sales_order", Integer.class);
        assertThat(orderCount).isEqualTo(1);

        Integer onHand = jdbcTemplate.queryForObject(
                "SELECT on_hand FROM inventory WHERE branch_id = 1 AND product_id = 101", Integer.class
        );
        assertThat(onHand).isEqualTo(48);

        mockMvc.perform(post("/api/v1/orders/pos")
                        .with(user("1").roles("CASHIER"))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        Integer finalOrderCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sales_order", Integer.class);
        assertThat(finalOrderCount).isEqualTo(1);
    }
}
