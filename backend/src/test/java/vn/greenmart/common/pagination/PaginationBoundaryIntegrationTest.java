package vn.greenmart.common.pagination;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.PostgresTestImage;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
@WithMockUser(username = "51601", roles = "OWNER")
@DisplayName("Issue #516: Kiểm thử ngưỡng phân trang int32 offset trên products, customers, promotions")
class PaginationBoundaryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);

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

    @BeforeEach
    void seedData() {
        jdbcTemplate.execute("""
            INSERT INTO app_user (id, username, password_hash, full_name, role, is_active)
            VALUES (51601, 'owner_516', '$2a$10$abcdefghijklmnopqrstuvwxyz123456', 'Owner 516', 'OWNER', true)
            ON CONFLICT (id) DO NOTHING;

            INSERT INTO category (id, name) VALUES (51601, 'Danh mục 516')
            ON CONFLICT (id) DO NOTHING;

            INSERT INTO product (id, sku, name, unit, category_id, sale_price, cost_price, is_active)
            VALUES (51601, 'SKU-516', 'Sản phẩm 516', 'hộp', 51601, 20000, 15000, true)
            ON CONFLICT (id) DO NOTHING;

            INSERT INTO customer (id, phone, name, is_active)
            VALUES (51601, '0905160001', 'Khách Hàng 516', true)
            ON CONFLICT (id) DO NOTHING;

            INSERT INTO promotion (id, code, type, level, value, start_at, end_at, status, used_count)
            VALUES (51601, 'KM-516', 'AMOUNT', 'ORDER', 10000, NOW() - INTERVAL '1 day', NOW() + INTERVAL '1 day', 'ACTIVE', 0)
            ON CONFLICT (id) DO NOTHING;
        """);
    }

    @ParameterizedTest(name = "{0} -> page=0, size=20 trả 200 có dữ liệu; page ngoài dữ liệu trả 200 content rỗng")
    @ValueSource(strings = {"/api/v1/products", "/api/v1/customers", "/api/v1/promotions"})
    void validPageZeroAndOutOfDataRange_Returns200EmptyPageResponse(String endpoint) throws Exception {
        mockMvc.perform(get(endpoint)
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content").isArray());

        mockMvc.perform(get(endpoint)
                        .param("page", "500")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(500))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @ParameterizedTest(name = "{0} -> ngưỡng hợp lệ size={1}, page={2} (offset <= 2147483647) trả 200 OK")
    @CsvSource({
            "/api/v1/products, 1, 2147483647",
            "/api/v1/products, 20, 107374182",
            "/api/v1/products, 100, 21474836",
            "/api/v1/customers, 1, 2147483647",
            "/api/v1/customers, 20, 107374182",
            "/api/v1/customers, 100, 21474836",
            "/api/v1/promotions, 1, 2147483647",
            "/api/v1/promotions, 20, 107374182",
            "/api/v1/promotions, 100, 21474836"
    })
    void validInt32OffsetThreshold_Returns200(String endpoint, int size, int page) throws Exception {
        mockMvc.perform(get(endpoint)
                        .param("page", String.valueOf(page))
                        .param("size", String.valueOf(size)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(page))
                .andExpect(jsonPath("$.size").value(size))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @ParameterizedTest(name = "{0} -> ngưỡng vượt int32 size={1}, page={2} trả 400 VALIDATION_ERROR ở field page")
    @CsvSource({
            "/api/v1/products, 20, 107374183, 107374182",
            "/api/v1/products, 100, 21474837, 21474836",
            "/api/v1/customers, 20, 107374183, 107374182",
            "/api/v1/customers, 100, 21474837, 21474836",
            "/api/v1/promotions, 20, 107374183, 107374182",
            "/api/v1/promotions, 100, 21474837, 21474836"
    })
    void overflowInt32OffsetThreshold_Returns400WithPageFieldAndSizeLimit(
            String endpoint,
            int size,
            int page,
            String expectedMaxPage
    ) throws Exception {
        mockMvc.perform(get(endpoint)
                        .param("page", String.valueOf(page))
                        .param("size", String.valueOf(size)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.details[0].field").value("page"))
                .andExpect(jsonPath("$.details[0].message", containsString(expectedMaxPage)));
    }

    @ParameterizedTest(name = "{0} -> page âm hoặc size sai trả 400 VALIDATION_ERROR đúng trường")
    @ValueSource(strings = {"/api/v1/products", "/api/v1/customers", "/api/v1/promotions"})
    void negativePageOrInvalidSize_Returns400(String endpoint) throws Exception {
        mockMvc.perform(get(endpoint)
                        .param("page", "-1")
                        .param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("page"));

        mockMvc.perform(get(endpoint)
                        .param("page", "0")
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("size"));

        mockMvc.perform(get(endpoint)
                        .param("page", "0")
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("size"));
    }
} 
