package vn.greenmart.promotion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.sales.domain.PaymentMethod;
import vn.greenmart.sales.domain.PaymentSimulation;
import vn.greenmart.sales.dto.IdempotencyResult;
import vn.greenmart.sales.dto.OrderLineRequest;
import vn.greenmart.sales.dto.OrderResponse;
import vn.greenmart.sales.dto.PaymentRequest;
import vn.greenmart.sales.dto.PosOrderRequest;
import vn.greenmart.sales.dto.PromotionRef;
import vn.greenmart.sales.dto.Quote;
import vn.greenmart.sales.service.PosIdempotencyService;
import vn.greenmart.sales.service.PosOrderService;
import vn.greenmart.sales.service.PromotionCalculator;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Issue #39: Bộ tính khuyến mại BR-07/BR-08 + Bảng ví dụ BA §7 + Cạnh tranh lượt dùng cuối EX-10")
class PromotionEngineIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            vn.greenmart.inventory.PostgresTestImage.IMAGE
    );

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PromotionCalculator promotionCalculator;

    @Autowired
    private PosOrderService posOrderService;

    @MockitoBean
    private PosIdempotencyService posIdempotencyService;

    private static final long BRANCH_ID = 801L;
    private static final long PRODUCT_ID = 801L;
    private static final long CASHIER_1_ID = 801L;
    private static final long CASHIER_2_ID = 802L;

    @BeforeEach
    void setUp() {
        cleanData();

        when(posIdempotencyService.execute(
                anyString(),
                any(),
                any()
        )).thenAnswer(invocation -> {
            Supplier<OrderResponse> action = invocation.getArgument(2);
            return new IdempotencyResult(true, action.get());
        });

        jdbcTemplate.execute("""
            INSERT INTO sales_channel (code, name, safety_stock)
            VALUES ('POS', 'Bán tại quầy', 0),
                   ('WEB', 'Website', 0),
                   ('MARKET', 'Sàn TMĐT', 0)
            ON CONFLICT (code) DO NOTHING
        """);
        jdbcTemplate.execute(String.format("""
            INSERT INTO branch (id, code, name, is_active)
            VALUES (%d, 'GM-39', 'Chi nhánh Test #39', true)
            ON CONFLICT (id) DO NOTHING
        """, BRANCH_ID));
        jdbcTemplate.execute("INSERT INTO category (id, name) VALUES (801, 'Danh mục #39') ON CONFLICT (id) DO NOTHING");
        jdbcTemplate.execute(String.format("""
            INSERT INTO product (id, sku, name, unit, category_id, sale_price, cost_price, is_active)
            VALUES (%d, 'SP-39-01', 'Gạo đặc sản 5kg', 'túi', 801, 250000, 190000, true)
            ON CONFLICT (id) DO NOTHING
        """, PRODUCT_ID));
        jdbcTemplate.execute(String.format("""
            INSERT INTO inventory (branch_id, product_id, on_hand, reserved, version, updated_at)
            VALUES (%d, %d, 50, 0, 1, NOW())
            ON CONFLICT DO NOTHING
        """, BRANCH_ID, PRODUCT_ID));
        jdbcTemplate.execute(String.format("""
            INSERT INTO app_user (id, username, password_hash, full_name, role, is_active)
            VALUES (%d, 'cashier_801', '$2a$10$abcdefghijklmnopqrstuvwxyz123456', 'Thu Ngân 1', 'CASHIER', true),
                   (%d, 'cashier_802', '$2a$10$abcdefghijklmnopqrstuvwxyz123456', 'Thu Ngân 2', 'CASHIER', true)
            ON CONFLICT (id) DO NOTHING
        """, CASHIER_1_ID, CASHIER_2_ID));
        jdbcTemplate.execute(String.format("""
            INSERT INTO user_branch (user_id, branch_id)
            VALUES (%d, %d), (%d, %d)
            ON CONFLICT DO NOTHING
        """, CASHIER_1_ID, BRANCH_ID, CASHIER_2_ID, BRANCH_ID));
    }

    @AfterEach
    void tearDown() {
        cleanData();
    }

    private void cleanData() {
        jdbcTemplate.execute("TRUNCATE TABLE inventory_transaction CASCADE");
        jdbcTemplate.execute("DELETE FROM outbox_event");
        jdbcTemplate.execute("DELETE FROM payment");
        jdbcTemplate.execute("DELETE FROM order_item");
        jdbcTemplate.execute("DELETE FROM sales_order");
        jdbcTemplate.execute("DELETE FROM order_sequence");
        jdbcTemplate.execute("DELETE FROM promotion_product");
        jdbcTemplate.execute("DELETE FROM promotion_branch");
        jdbcTemplate.execute("DELETE FROM promotion_channel");
        jdbcTemplate.execute("DELETE FROM promotion");
        jdbcTemplate.execute("DELETE FROM inventory WHERE branch_id = " + BRANCH_ID);
        jdbcTemplate.execute("DELETE FROM product WHERE id = " + PRODUCT_ID);
        jdbcTemplate.execute("DELETE FROM category WHERE id = 801");
        jdbcTemplate.execute("DELETE FROM user_branch WHERE user_id IN (801, 802)");
        jdbcTemplate.execute("DELETE FROM app_user WHERE id IN (801, 802)");
        jdbcTemplate.execute("DELETE FROM branch WHERE id = " + BRANCH_ID);
    }

    @ParameterizedTest(name = "[{index}] subtotalAfterLines={0}đ, channel={1} -> expectedCode={2}, expectedDiscount={3}đ")
    @CsvSource(nullValues = "NULL", value = {
            "180000, POS, NULL, 0",
            "223000, POS, KM-10, 22300",
            "260000, POS, KM-10, 26000",
            "350000, POS, KM-10, 30000",
            "240000, MARKET, NULL, 0"
    })
    @DisplayName("Bộ Test 1: Khóa chính xác 5 dòng bảng ví dụ BA mục 7 (BR-07)")
    void testBaSection7ExampleTable_Parameterized(
            long subtotalAfterLineDiscounts,
            String channelCode,
            String expectedPromoCode,
            long expectedDiscount
    ) {
        // KM-10: Giảm 10%, minOrder = 200.000 đ, maxDiscount = 30.000 đ, kênh [POS, WEB]
        jdbcTemplate.execute("""
            INSERT INTO promotion (id, code, type, level, value, max_discount, min_order, start_at, end_at, usage_limit, used_count, status)
            VALUES (8101, 'KM-10', 'PERCENT', 'ORDER', 10, 30000, 200000, NOW() - INTERVAL '1 hour', NOW() + INTERVAL '5 days', NULL, 0, 'ACTIVE')
        """);
        jdbcTemplate.execute("""
            INSERT INTO promotion_channel (promotion_id, channel_id)
            SELECT 8101, id FROM sales_channel WHERE code IN ('POS', 'WEB')
        """);

        // KM-25K: Giảm 25.000 đ, minOrder = 300.000 đ, kênh [POS, WEB, MARKET]
        jdbcTemplate.execute("""
            INSERT INTO promotion (id, code, type, level, value, max_discount, min_order, start_at, end_at, usage_limit, used_count, status)
            VALUES (8102, 'KM-25K', 'AMOUNT', 'ORDER', 25000, NULL, 300000, NOW() - INTERVAL '1 hour', NOW() + INTERVAL '5 days', NULL, 0, 'ACTIVE')
        """);
        jdbcTemplate.execute("""
            INSERT INTO promotion_channel (promotion_id, channel_id)
            SELECT 8102, id FROM sales_channel WHERE code IN ('POS', 'WEB', 'MARKET')
        """);

        PromotionRef chosen = promotionCalculator.evaluateOrderPromotion(subtotalAfterLineDiscounts, BRANCH_ID, channelCode);

        if (expectedPromoCode == null) {
            assertThat(chosen).isNull();
        } else {
            assertThat(chosen).isNotNull();
            assertThat(chosen.code()).isEqualTo(expectedPromoCode);
            assertThat(chosen.discount()).isEqualTo(expectedDiscount);
        }
    }

    @Test
    @DisplayName("Bộ Test 2: Hai KM cấp đơn giảm bằng nhau (20.000đ) -> Chọn KM có endAt sớm hơn (KM-A)")
    void testTieBreaker_EqualDiscount_SelectsEarlierEndAt() {
        Instant now = Instant.now();
        Instant earlierEnd = now.plus(2, ChronoUnit.DAYS);
        Instant laterEnd = now.plus(7, ChronoUnit.DAYS);

        jdbcTemplate.update("""
            INSERT INTO promotion (id, code, type, level, value, min_order, start_at, end_at, status)
            VALUES (?, 'KM-B', 'AMOUNT', 'ORDER', 20000, 100000, ?, ?, 'ACTIVE')
        """, 8201L, java.sql.Timestamp.from(now.minus(1, ChronoUnit.HOURS)), java.sql.Timestamp.from(laterEnd));

        jdbcTemplate.update("""
            INSERT INTO promotion (id, code, type, level, value, min_order, start_at, end_at, status)
            VALUES (?, 'KM-A', 'PERCENT', 'ORDER', 10, 100000, ?, ?, 'ACTIVE')
        """, 8202L, java.sql.Timestamp.from(now.minus(1, ChronoUnit.HOURS)), java.sql.Timestamp.from(earlierEnd));

        // Đơn 200.000đ: KM-B giảm 20.000đ, KM-A (10%) cũng giảm 20.000đ -> ưu tiên KM-A vì hết hạn sớm hơn
        PromotionRef chosen = promotionCalculator.evaluateOrderPromotion(200000L, BRANCH_ID, "POS");

        assertThat(chosen).isNotNull();
        assertThat(chosen.code()).isEqualTo("KM-A");
        assertThat(chosen.discount()).isEqualTo(20000L);
    }

    @Test
    @DisplayName("Bộ Test 3: Cạnh tranh lượt dùng cuối cùng (EX-10, BR-08) -> 1 đơn thành công được giảm, 1 đơn 422 TOTAL_CHANGED, used_count = 100")
    void testConcurrentLastUsageSlot_OnlyOneWins_OtherGets422TotalChanged() throws Exception {
        // KM còn đúng 1 lượt dùng cuối cùng: usageLimit = 100, usedCount = 99
        jdbcTemplate.execute("""
            INSERT INTO promotion (id, code, type, level, value, min_order, start_at, end_at, usage_limit, used_count, status)
            VALUES (8301, 'KM-LAST-SLOT', 'AMOUNT', 'ORDER', 20000, 200000, NOW() - INTERVAL '1 hour', NOW() + INTERVAL '1 day', 100, 99, 'ACTIVE')
        """);

        // Sản phẩm giá 250.000đ, giảm 20.000đ -> expectedTotal = 230.000đ
        PosOrderRequest posRequest = new PosOrderRequest(
                BRANCH_ID,
                null,
                List.of(new OrderLineRequest(PRODUCT_ID, 1)),
                null,
                new PaymentRequest(PaymentMethod.CASH, 250000L, PaymentSimulation.SUCCESS),
                230000L
        );

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        record ThreadOutcome(int status, OrderResponse order, AppException error) {}

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<ThreadOutcome> f1 = executor.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                CASHIER_1_ID, null, List.of(new SimpleGrantedAuthority("ROLE_CASHIER"))
                        )
                );
                try {
                    readyLatch.countDown();
                    startLatch.await(10, TimeUnit.SECONDS);
                    var res = posOrderService.createPosOrder(posRequest, UUID.randomUUID().toString());
                    return new ThreadOutcome(201, res, null);
                } catch (AppException ex) {
                    int status = ErrorCode.valueOf(ex.getCode()).getHttpStatus().value();
                    return new ThreadOutcome(status, null, ex);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });

            Future<ThreadOutcome> f2 = executor.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                CASHIER_2_ID, null, List.of(new SimpleGrantedAuthority("ROLE_CASHIER"))
                        )
                );
                try {
                    readyLatch.countDown();
                    startLatch.await(10, TimeUnit.SECONDS);
                    var res = posOrderService.createPosOrder(posRequest, UUID.randomUUID().toString());
                    return new ThreadOutcome(201, res, null);
                } catch (AppException ex) {
                    int status = ErrorCode.valueOf(ex.getCode()).getHttpStatus().value();
                    return new ThreadOutcome(status, null, ex);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });

            assertThat(readyLatch.await(10, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            ThreadOutcome r1 = f1.get(20, TimeUnit.SECONDS);
            ThreadOutcome r2 = f2.get(20, TimeUnit.SECONDS);

            Integer usedAfter = jdbcTemplate.queryForObject(
                    "SELECT used_count FROM promotion WHERE id = 8301", Integer.class
            );
            assertThat(List.of(r1.status(), r2.status()))
                    .withFailMessage("r1=%s, r2=%s, used_count_in_db=%s", r1, r2, usedAfter)
                    .containsExactlyInAnyOrder(201, 422);

            ThreadOutcome winner = (r1.status() == 201) ? r1 : r2;
            ThreadOutcome loser = (r1.status() == 422) ? r1 : r2;

            assertThat(winner.order()).isNotNull();
            assertThat(winner.order().total()).isEqualTo(230000L);
            assertThat(winner.order().orderDiscount()).isEqualTo(20000L);
            assertThat(winner.order().orderPromotion().code()).isEqualTo("KM-LAST-SLOT");

            assertThat(loser.error()).isNotNull();
            assertThat(loser.error().getCode()).isEqualTo("TOTAL_CHANGED");
            Map<?, ?> detailsMap = (Map<?, ?>) loser.error().getDetails();
            Quote updatedQuote = (Quote) detailsMap.get("quote");
            assertThat(updatedQuote).isNotNull();
            assertThat(updatedQuote.total()).isEqualTo(250000L);
            assertThat(updatedQuote.orderPromotion()).isNull();
        }

        // Xác minh trong PostgreSQL thật: used_count dừng ở 100, tuyệt đối không vượt usage_limit
        Integer finalUsedCount = jdbcTemplate.queryForObject(
                "SELECT used_count FROM promotion WHERE id = 8301", Integer.class
        );
        assertThat(finalUsedCount).isEqualTo(100);
    }

    @Test
    @WithMockUser(username = "801", roles = "CASHIER")
    @DisplayName("Kết hợp KM cấp sản phẩm (ITEM) + KM cấp đơn (ORDER) qua API /orders/quote")
    void testItemAndOrderPromotionsCombined_InQuoteEndpoint() throws Exception {
        // KM cấp dòng sản phẩm: Giảm 27.000đ cho sản phẩm 801 -> 250.000 - 27.000 = 223.000đ
        jdbcTemplate.execute("""
            INSERT INTO promotion (id, code, type, level, value, start_at, end_at, status)
            VALUES (8401, 'KM-ITEM-27K', 'AMOUNT', 'ITEM', 27000, NOW() - INTERVAL '1 hour', NOW() + INTERVAL '1 day', 'ACTIVE')
        """);
        jdbcTemplate.execute("INSERT INTO promotion_product (promotion_id, product_id) VALUES (8401, 801)");

        // KM cấp đơn: Giảm 10% cho đơn sau giảm dòng từ 200.000đ (tối đa 30k) -> 10% của 223.000 = 22.300đ
        jdbcTemplate.execute("""
            INSERT INTO promotion (id, code, type, level, value, max_discount, min_order, start_at, end_at, status)
            VALUES (8402, 'KM-10', 'PERCENT', 'ORDER', 10, 30000, 200000, NOW() - INTERVAL '1 hour', NOW() + INTERVAL '1 day', 'ACTIVE')
        """);

        String quoteBody = """
            {
              "branchId": 801,
              "items": [{ "productId": 801, "quantity": 1 }]
            }
        """;

        mockMvc.perform(post("/api/v1/orders/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(quoteBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(250000))
                .andExpect(jsonPath("$.itemDiscount").value(27000))
                .andExpect(jsonPath("$.orderDiscount").value(22300))
                .andExpect(jsonPath("$.discount").value(49300))
                .andExpect(jsonPath("$.total").value(200700));
    }
}
