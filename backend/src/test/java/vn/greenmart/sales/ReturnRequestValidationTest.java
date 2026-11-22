package vn.greenmart.sales;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import vn.greenmart.inventory.PostgresTestImage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
@WithMockUser(username = "48301", roles = "CASHIER")
class ReturnRequestValidationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    private static final String ITEM = "{\"orderItemId\":48301,\"quantity\":1,\"condition\":\"RESELLABLE\"}";

    @BeforeEach
    void fixture() {
        jdbc.execute("""
                INSERT INTO branch(id,code,name,is_active) VALUES(48301,'GM-483','Validation branch',true);
                INSERT INTO category(id,name) VALUES(48301,'Validation category');
                INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price,is_active)
                    VALUES(48301,'QA-483','Validation product','box',48301,100,50,true);
                INSERT INTO app_user(id,username,password_hash,full_name,role,is_active)
                    VALUES(48301,'cashier_483','test-only-unused-hash','Validation cashier','CASHIER',true);
                INSERT INTO user_branch(user_id,branch_id) VALUES(48301,48301);
                INSERT INTO inventory(branch_id,product_id,on_hand,reserved,version) VALUES(48301,48301,3,0,0);
                INSERT INTO sales_order(id,code,channel_id,branch_id,status,subtotal,discount,total,
                    created_by,created_at,completed_at)
                    SELECT 48301,'POS-483',id,48301,'COMPLETED',200,0,200,48301,
                        now()-interval '1 day',now()-interval '1 day' FROM sales_channel WHERE code='POS';
                INSERT INTO order_item(id,order_id,product_id,quantity,unit_price,discount,line_total)
                    VALUES(48301,48301,48301,2,100,0,200);
                """);
    }

    static Stream<Arguments> nullElements() {
        return Stream.of(
                Arguments.of("[null]", "items[0]"),
                Arguments.of("[null," + ITEM + "]", "items[0]"),
                Arguments.of("[" + ITEM + ",null]", "items[1]"),
                Arguments.of("[" + ITEM + ",null," + ITEM + "]", "items[1]"),
                Arguments.of("[null,null]", "items[0]"));
    }

    @ParameterizedTest
    @MethodSource("nullElements")
    void rejectsNullElementWithFieldErrorAndNoMutation(String items, String field) throws Exception {
        var before = snapshot();
        mvc.perform(post("/api/v1/returns").contentType(MediaType.APPLICATION_JSON)
                        .content(body(",\"items\":" + items)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[*].field").value(hasItem(field)));
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"items\":null", ",\"items\":[]", ",\"items\":[{}]",
            ",\"items\":[{\"quantity\":1,\"condition\":\"RESELLABLE\"}]",
            ",\"items\":[{\"orderItemId\":48301,\"condition\":\"RESELLABLE\"}]",
            ",\"items\":[{\"orderItemId\":48301,\"quantity\":1}]"})
    void rejectsMissingListOrRequiredItemFieldsWithoutMutation(String fragment) throws Exception {
        var before = snapshot();
        mvc.perform(post("/api/v1/returns").contentType(MediaType.APPLICATION_JSON).content(body(fragment)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void validItemStillCreatesDraftWithCorrectQuantityAndRefund() throws Exception {
        var stock = jdbc.queryForList("SELECT * FROM inventory ORDER BY branch_id,product_id");
        mvc.perform(post("/api/v1/returns").contentType(MediaType.APPLICATION_JSON)
                        .content(body(",\"items\":[" + ITEM + "]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.refundAmount").value(100))
                .andExpect(jsonPath("$.items[0].quantity").value(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM return_document WHERE order_id=48301", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM inventory ORDER BY branch_id,product_id")).isEqualTo(stock);
    }

    private String body(String fragment) {
        return "{\"orderId\":48301,\"branchId\":48301,\"reason\":\"Validation regression\"" + fragment + "}";
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String table : List.of("return_document", "return_item", "sales_order", "inventory_transaction",
                "payment", "audit_log", "outbox_event")) {
            snapshot.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        }
        snapshot.put("inventory", jdbc.queryForList("SELECT * FROM inventory ORDER BY branch_id,product_id"));
        return snapshot;
    }
}
