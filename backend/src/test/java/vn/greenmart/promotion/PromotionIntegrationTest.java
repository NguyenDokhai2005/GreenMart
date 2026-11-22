package vn.greenmart.promotion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        jdbcTemplate.execute("""
            INSERT INTO sales_channel (code, name, safety_stock)
            VALUES ('POS', 'Bán tại quầy', 0),
                   ('WEB', 'Website', 0),
                   ('MARKET', 'Sàn TMĐT', 0)
            ON CONFLICT (code) DO NOTHING
        """);
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
        mockMvc.perform(get("/api/v1/promotions/{id}", promoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(promoId))
                .andExpect(jsonPath("$.code").value("DON200K-10"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.branchIds[0]").value(701))
                .andExpect(jsonPath("$.channels[0]").value("POS"));
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

    @Test
    @WithMockUser(username = "701", roles = "OWNER")
    @DisplayName("Phân trang 2 trang có lọc channel=POS: tổng đếm đúng 3 KM (2 KM POS + 1 KM toàn kênh), loại KM chỉ WEB")
    void testListPromotions_PaginationWithChannelFilter_ReturnsExactTotalsAndPages() throws Exception {
        Instant startAt = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant endAt = Instant.now().plus(5, ChronoUnit.DAYS);

        // Tạo 4 KM theo thứ tự ID tăng dần:
        // 1. KM-POS-1 (chỉ POS) -> khớp
        // 2. KM-WEB-ONLY (chỉ WEB) -> bị loại
        // 3. KM-POS-2 (chỉ POS) -> khớp
        // 4. KM-ALL-CH (không giới hạn kênh -> toàn kênh) -> khớp
        List<PromotionRequest> requests = List.of(
                new PromotionRequest("KM-POS-1", PromotionType.AMOUNT, PromotionLevel.ORDER, 10000L, null, 100000L, startAt, endAt, null, null, null, List.of("POS")),
                new PromotionRequest("KM-WEB-ONLY", PromotionType.AMOUNT, PromotionLevel.ORDER, 15000L, null, 100000L, startAt, endAt, null, null, null, List.of("WEB")),
                new PromotionRequest("KM-POS-2", PromotionType.AMOUNT, PromotionLevel.ORDER, 20000L, null, 100000L, startAt, endAt, null, null, null, List.of("POS")),
                new PromotionRequest("KM-ALL-CH", PromotionType.AMOUNT, PromotionLevel.ORDER, 25000L, null, 100000L, startAt, endAt, null, null, null, List.of())
        );

        for (PromotionRequest r : requests) {
            mockMvc.perform(post("/api/v1/promotions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(r)))
                    .andExpect(status().isCreated());
        }

        // Trang 1 (page=1, size=2, sort=id,asc, channel=POS): phải trả về đủ 2 dòng [KM-POS-1, KM-POS-2], totalElements=3, totalPages=2
        mockMvc.perform(get("/api/v1/promotions")
                        .param("channel", "POS")
                        .param("page", "0")
                        .param("size", "2")
                        .param("sort", "id,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].code").value("KM-POS-1"))
                .andExpect(jsonPath("$.content[1].code").value("KM-POS-2"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        // Trang 2 (page=2, size=2, sort=id,asc, channel=POS): trả về 1 dòng còn lại [KM-ALL-CH], totalElements=3, totalPages=2
        mockMvc.perform(get("/api/v1/promotions")
                        .param("channel", "POS")
                        .param("page", "1")
                        .param("size", "2")
                        .param("sort", "id,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].code").value("KM-ALL-CH"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    @WithMockUser(username = "701", roles = "OWNER")
    @DisplayName("Issue #406 Regression: Phần tử null trong channels, productIds, branchIds khi POST/PUT trả 400 VALIDATION_ERROR và không đổi DB")
    void testIssue406_NullElementsInScopeLists_PostAndPut_Return400ValidationError() throws Exception {
        String validDraftBody = """
            {
              "code": "QA-NULL-CONTROL",
              "type": "PERCENT",
              "level": "ITEM",
              "value": 10,
              "startAt": "2030-11-19T00:00:00Z",
              "endAt": "2030-11-20T00:00:00Z",
              "productIds": [701],
              "branchIds": [701],
              "channels": ["POS"]
            }
        """;

        String createdJson = mockMvc.perform(post("/api/v1/promotions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long draftId = objectMapper.readTree(createdJson).get("id").asLong();

        var invalidPayloads = List.of(
                java.util.Map.entry("channels[0]", """
                    {
                      "code": "QA-NULL-CH",
                      "type": "PERCENT",
                      "level": "ITEM",
                      "value": 10,
                      "startAt": "2030-11-19T00:00:00Z",
                      "endAt": "2030-11-20T00:00:00Z",
                      "productIds": [701],
                      "branchIds": [701],
                      "channels": [null]
                    }
                """),
                java.util.Map.entry("productIds[0]", """
                    {
                      "code": "QA-NULL-PROD",
                      "type": "PERCENT",
                      "level": "ITEM",
                      "value": 10,
                      "startAt": "2030-11-19T00:00:00Z",
                      "endAt": "2030-11-20T00:00:00Z",
                      "productIds": [null],
                      "branchIds": [701],
                      "channels": ["POS"]
                    }
                """),
                java.util.Map.entry("branchIds[0]", """
                    {
                      "code": "QA-NULL-BR",
                      "type": "PERCENT",
                      "level": "ITEM",
                      "value": 10,
                      "startAt": "2030-11-19T00:00:00Z",
                      "endAt": "2030-11-20T00:00:00Z",
                      "productIds": [701],
                      "branchIds": [null],
                      "channels": ["POS"]
                    }
                """)
        );

        for (var entry : invalidPayloads) {
            String expectedField = entry.getKey();
            String body = entry.getValue();

            mockMvc.perform(post("/api/v1/promotions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.details[0].field").value(expectedField));

            mockMvc.perform(put("/api/v1/promotions/{id}", draftId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.details[0].field").value(expectedField));
        }

        Integer totalPromos = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM promotion", Integer.class);
        assertEquals(1, totalPromos);

        mockMvc.perform(get("/api/v1/promotions/{id}", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("QA-NULL-CONTROL"))
                .andExpect(jsonPath("$.productIds[0]").value(701))
                .andExpect(jsonPath("$.branchIds[0]").value(701))
                .andExpect(jsonPath("$.channels[0]").value("POS"));
    }

    @Test
    @WithMockUser(username = "701", roles = "OWNER")
    @DisplayName("Regression 4 quy tắc nghiệp vụ: PERCENT>100, AMOUNT+maxDiscount, endAt<=startAt, usageLimit<usedCount -> 400 VALIDATION_ERROR đúng field & giữ nguyên DB/audit")
    void testBusinessRulesRegression_400WithExactField_AndValidBoundaryValues_Return200() throws Exception {
        record RuleCase(String expectedField, String body) {}

        // Tạo 1 KM DRAFT làm control cho PUT
        String controlBody = """
            {
              "code": "QA-RULE-CTRL",
              "type": "PERCENT",
              "level": "ORDER",
              "value": 10,
              "startAt": "2030-11-19T00:00:00Z",
              "endAt": "2030-11-20T00:00:00Z",
              "usageLimit": 10
            }
        """;
        String createdJson = mockMvc.perform(post("/api/v1/promotions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(controlBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long draftId = objectMapper.readTree(createdJson).get("id").asLong();

        // 1. PERCENT > 100 (value = 101) -> field: value
        String percentOver100 = """
            {
              "code": "QA-PCT-101",
              "type": "PERCENT",
              "level": "ORDER",
              "value": 101,
              "startAt": "2030-11-19T00:00:00Z",
              "endAt": "2030-11-20T00:00:00Z"
            }
        """;
        // 2. AMOUNT kèm maxDiscount -> field: maxDiscount
        String amountWithMaxDiscount = """
            {
              "code": "QA-AMT-CAP",
              "type": "AMOUNT",
              "level": "ORDER",
              "value": 10000,
              "maxDiscount": 1000,
              "startAt": "2030-11-19T00:00:00Z",
              "endAt": "2030-11-20T00:00:00Z"
            }
        """;
        // 3a. endAt == startAt -> field: endAt
        String endEqualStart = """
            {
              "code": "QA-END-EQ",
              "type": "AMOUNT",
              "level": "ORDER",
              "value": 10000,
              "startAt": "2030-11-19T00:00:00Z",
              "endAt": "2030-11-19T00:00:00Z"
            }
        """;
        // 3b. endAt < startAt -> field: endAt
        String endBeforeStart = """
            {
              "code": "QA-END-LT",
              "type": "AMOUNT",
              "level": "ORDER",
              "value": 10000,
              "startAt": "2030-11-20T00:00:00Z",
              "endAt": "2030-11-19T00:00:00Z"
            }
        """;

        List<RuleCase> postAndPutCases = List.of(
                new RuleCase("value", percentOver100),
                new RuleCase("maxDiscount", amountWithMaxDiscount),
                new RuleCase("endAt", endEqualStart),
                new RuleCase("endAt", endBeforeStart)
        );

        for (RuleCase tc : postAndPutCases) {
            mockMvc.perform(post("/api/v1/promotions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(tc.body()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.details[0].field").value(tc.expectedField()));

            mockMvc.perform(put("/api/v1/promotions/{id}", draftId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(tc.body()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.details[0].field").value(tc.expectedField()));
        }

        // Xác minh không phát sinh bản ghi mới và audit_log chỉ có 1 dòng CREATE_PROMOTION ban đầu
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM promotion", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class));

        // 4. PUT khuyến mại PAUSED đã dùng 5 lượt, giảm usageLimit từ 10 xuống 4 -> 400 VALIDATION_ERROR, field: usageLimit
        jdbcTemplate.update("UPDATE promotion SET status = 'PAUSED', used_count = 5 WHERE id = ?", draftId);

        String reduceLimitBelowUsed = """
            {
              "code": "QA-RULE-CTRL",
              "type": "PERCENT",
              "level": "ORDER",
              "value": 10,
              "startAt": "2030-11-19T00:00:00Z",
              "endAt": "2030-11-20T00:00:00Z",
              "usageLimit": 4
            }
        """;
        mockMvc.perform(put("/api/v1/promotions/{id}", draftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reduceLimitBelowUsed))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("usageLimit"));

        // Kiểm tra DB giữ nguyên usage_limit = 10 và audit_log vẫn chỉ có 1 bản ghi
        org.junit.jupiter.api.Assertions.assertEquals(10, jdbcTemplate.queryForObject("SELECT usage_limit FROM promotion WHERE id = ?", Integer.class, draftId));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class));

        // 5. Biên hợp lệ: PERCENT = 100 và usageLimit == usedCount (5) -> 200 OK
        String validBoundaryUpdate = """
            {
              "code": "QA-RULE-CTRL",
              "type": "PERCENT",
              "level": "ORDER",
              "value": 100,
              "startAt": "2030-11-19T00:00:00Z",
              "endAt": "2030-11-20T00:00:00Z",
              "usageLimit": 5
            }
        """;
        mockMvc.perform(put("/api/v1/promotions/{id}", draftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBoundaryUpdate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(100))
                .andExpect(jsonPath("$.usageLimit").value(5));
    }
}
