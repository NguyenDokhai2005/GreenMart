package vn.greenmart.promotion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.promotion.domain.PromotionLevel;
import vn.greenmart.promotion.domain.PromotionStatus;
import vn.greenmart.promotion.domain.PromotionType;
import vn.greenmart.promotion.dto.PromotionRequest;
import vn.greenmart.promotion.dto.PromotionStatusRequest;
import vn.greenmart.sales.dto.OrderLineRequest;
import vn.greenmart.sales.dto.QuoteRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Issue #38: Module promotion - CRUD, trạng thái, phạm vi (PRM-01, PRM-03, BR-07, BR-08)")
class PromotionIntegrationTest {

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
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanData();
        jdbcTemplate.execute("INSERT INTO sales_channel (code, name, safety_stock) VALUES ('POS', 'Bán tại quầy', 0) ON CONFLICT (code) DO NOTHING");
        jdbcTemplate.execute("INSERT INTO branch (id, code, name, is_active) VALUES (701, 'GM-PRM-1', 'CN Khuyến Mại 1', true) ON CONFLICT (id) DO NOTHING");
        jdbcTemplate.execute("INSERT INTO category (id, name) VALUES (701, 'Danh mục KM') ON CONFLICT (id) DO NOTHING");
        jdbcTemplate.execute("""
            INSERT INTO product (id, sku, name, unit, category_id, sale_price, cost_price, is_active)
            VALUES (701, 'SP-PRM-701', 'Sữa tươi KM', 'hộp', 701, 250000, 180000, true)
            ON CONFLICT (id) DO NOTHING
        """);
        jdbcTemplate.execute("INSERT INTO inventory (branch_id, product_id, on_hand, reserved, version, updated_at) VALUES (701, 701, 50, 0, 1, NOW()) ON CONFLICT DO NOTHING");
        jdbcTemplate.execute("""
            INSERT INTO app_user (id, username, password_hash, full_name, role, is_active)
            VALUES (701, 'owner_701', '$2a$10$abcdefghijklmnopqrstuvwxyz123456', 'Chủ Chuỗi', 'OWNER', true),
                   (702, 'manager_702', '$2a$10$abcdefghijklmnopqrstuvwxyz123456', 'QL Chi Nhánh', 'BRANCH_MANAGER', true)
            ON CONFLICT (id) DO NOTHING
        """);
        jdbcTemplate.execute("INSERT INTO user_branch (user_id, branch_id) VALUES (702, 701) ON CONFLICT DO NOTHING");
    }

    @AfterEach
    void tearDown() {
        cleanData();
    }

    private void cleanData() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_log");
        jdbcTemplate.execute("DELETE FROM promotion_product");
        jdbcTemplate.execute("DELETE FROM promotion_branch");
        jdbcTemplate.execute("DELETE FROM promotion_channel");
        jdbcTemplate.execute("DELETE FROM promotion");
        jdbcTemplate.execute("DELETE FROM inventory WHERE branch_id = 701");
        jdbcTemplate.execute("DELETE FROM product WHERE id = 701");
        jdbcTemplate.execute("DELETE FROM category WHERE id = 701");
        jdbcTemplate.execute("DELETE FROM user_branch WHERE user_id IN (701, 702)");
        jdbcTemplate.execute("DELETE FROM app_user WHERE id IN (701, 702)");
        jdbcTemplate.execute("DELETE FROM branch WHERE id = 701");
    }

    @Test
    @WithMockUser(username = "701", roles = "OWNER")
    @DisplayName("AC1 & AC2: OWNER tạo KM cấp ORDER 10% tối đa 30k, đơn từ 200k -> 201 DRAFT; kích hoạt ACTIVE -> áp ở quote")
    void testAC1_And_AC2_CreateDraft_Activate_AppliesInQuote() throws Exception {
        Instant startAt = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant endAt = Instant.now().plus(7, ChronoUnit.DAYS);

        PromotionRequest req = new PromotionRequest(
                "DON200K-10",
                PromotionType.PERCENT,
                PromotionLevel.ORDER,
                10L,
                30000L,
                200000L,
                startAt,
                endAt,
                500,
                null,
                List.of(701L),
                List.of("POS")
        );

        String responseJson = mockMvc.perform(post("/api/v1/promotions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("DON200K-10"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.usedCount").value(0))
                .andExpect(jsonPath("$.branchIds", hasSize(1)))
                .andReturn().getResponse().getContentAsString();

        long promoId = objectMapper.readTree(responseJson).get("id").asLong();

        // Kích hoạt DRAFT -> ACTIVE
        mockMvc.perform(post("/api/v1/promotions/{id}/status", promoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PromotionStatusRequest(PromotionStatus.ACTIVE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Kiểm tra áp dụng ngay ở /orders/quote (đơn 250.000đ -> giảm 10% = 25.000đ -> còn 225.000đ)
        QuoteRequest quoteReq = new QuoteRequest(701L, null, List.of(new OrderLineRequest(701L, 1)), null);
        mockMvc.perform(post("/api/v1/orders/quote")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("702").roles("BRANCH_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(quoteReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderPromotion.code").value("DON200K-10"))
                .andExpect(jsonPath("$.orderDiscount").value(25000))
                .andExpect(jsonPath("$.total").value(225000));
    }

    @Test
    @WithMockUser(username = "701", roles = "OWNER")
    @DisplayName("AC3 & AC7: KM đã ENDED chuyển sang ACTIVE -> 409 INVALID_STATUS; gọi PUT khi ACTIVE -> 409 INVALID_STATUS")
    void testAC3_And_AC7_InvalidStatusTransitions_Return409() throws Exception {
        PromotionRequest req = new PromotionRequest(
                "KM-STATE-01",
                PromotionType.AMOUNT,
                PromotionLevel.ORDER,
                15000L,
                null,
                100000L,
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(2, ChronoUnit.DAYS),
                100,
                null,
                null,
                null
        );

        String createdJson = mockMvc.perform(post("/api/v1/promotions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long promoId = objectMapper.readTree(createdJson).get("id").asLong();

        // DRAFT -> ACTIVE
        mockMvc.perform(post("/api/v1/promotions/{id}/status", promoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PromotionStatusRequest(PromotionStatus.ACTIVE))))
                .andExpect(status().isOk());

        // AC7: Cố tình sửa (PUT) khi đang ACTIVE -> 409 INVALID_STATUS
        mockMvc.perform(put("/api/v1/promotions/{id}", promoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        // ACTIVE -> ENDED
        mockMvc.perform(post("/api/v1/promotions/{id}/status", promoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PromotionStatusRequest(PromotionStatus.ENDED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"));

        // AC3: ENDED -> ACTIVE bị từ chối 409 INVALID_STATUS
        mockMvc.perform(post("/api/v1/promotions/{id}/status", promoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PromotionStatusRequest(PromotionStatus.ACTIVE))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
    }

    @Test
    @WithMockUser(username = "701", roles = "OWNER")
    @DisplayName("AC4: Tạo KM cấp ITEM không có productIds -> 400 VALIDATION_ERROR")
    void testAC4_ItemLevelWithoutProductIds_Returns400() throws Exception {
        PromotionRequest req = new PromotionRequest(
                "ITEM-NO-PROD",
                PromotionType.AMOUNT,
                PromotionLevel.ITEM,
                10000L,
                null,
                null,
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(2, ChronoUnit.DAYS),
                null,
                List.of(),
                null,
                null
        );

        mockMvc.perform(post("/api/v1/promotions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(username = "701", roles = "OWNER")
    @DisplayName("AC5: Tạo KM trùng mã code đã tồn tại -> 409 DUPLICATE_PROMOTION_CODE")
    void testAC5_DuplicateCode_Returns409() throws Exception {
        PromotionRequest req = new PromotionRequest(
                "DUP-CODE-01",
                PromotionType.AMOUNT,
                PromotionLevel.ORDER,
                20000L,
                null,
                100000L,
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(2, ChronoUnit.DAYS),
                null,
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/v1/promotions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/promotions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_PROMOTION_CODE"));
    }

    @Test
    @WithMockUser(username = "702", roles = "BRANCH_MANAGER")
    @DisplayName("AC6: BRANCH_MANAGER gọi tạo KM -> 403 FORBIDDEN; gọi GET danh sách -> 200 OK")
    void testAC6_BranchManagerCreateForbidden_ListAllowed() throws Exception {
        PromotionRequest req = new PromotionRequest(
                "MGR-TRY-01",
                PromotionType.AMOUNT,
                PromotionLevel.ORDER,
                10000L,
                null,
                50000L,
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(2, ChronoUnit.DAYS),
                null,
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/v1/promotions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/v1/promotions"))
                .andExpect(status().isOk());
    }
}
