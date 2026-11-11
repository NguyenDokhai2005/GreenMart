package vn.greenmart.inventory;

import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vn.greenmart.sales.service.PosIdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "management.endpoint.health.group.readiness.include=readinessState,db"
})
@AutoConfigureMockMvc
@Testcontainers
class StockTransferApiTest {
    @MockitoBean PosIdempotencyService posIdempotencyService;
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.forecast_user", () -> "forecast_ro");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private static final String DOCUMENTS = "/api/v1/stock-documents";

    @BeforeEach void fixtures() {
        jdbc.execute("TRUNCATE branch,category,app_user CASCADE");
        jdbc.update("INSERT INTO branch(id,code,name) VALUES(81,'SRC','Source'),(82,'DST','Destination'),(83,'OTH','Other')");
        jdbc.update("INSERT INTO category(id,name) VALUES(81,'Milk')");
        jdbc.update("""
                INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price)
                VALUES(81,'MILK','Milk','hop',81,29000,10000)
                """);
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,full_name,role) VALUES
                (810,'maker','hash','Maker','STOREKEEPER'),
                (811,'manager','hash','Manager','BRANCH_MANAGER'),
                (812,'receiver','hash','Receiver','STOREKEEPER'),
                (813,'outsider','hash','Outsider','STOREKEEPER')
                """);
        jdbc.update("INSERT INTO user_branch(user_id,branch_id) VALUES(810,81),(811,81),(812,82),(813,83)");
        jdbc.update("INSERT INTO inventory(branch_id,product_id,on_hand,reserved) VALUES(81,81,85,5)");
    }

    @Test void branchAccessIsCheckedBeforeUnsupportedDocumentType() throws Exception {
        long id = jdbc.queryForObject("""
                INSERT INTO stock_document(code,type,branch_id,created_by)
                VALUES ('ADJUST-TEST','ADJUST',81,810) RETURNING id
                """, Long.class);
        mvc.perform(get(DOCUMENTS + "/{id}", id).with(user("813").roles("STOREKEEPER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
        mvc.perform(post(DOCUMENTS + "/{id}/approve", id).with(user("813").roles("STOREKEEPER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
        mvc.perform(get(DOCUMENTS + "/{id}", id).with(user("810").roles("STOREKEEPER")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test void dispatchAndReceiveShortageRecordTheActualMovements() throws Exception {
        var draft = draft();
        long id = draft.get("id").asLong();
        assertThat(onHand(81)).isEqualTo(85);
        assertThat(onHand(82)).isNull();

        approve(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty()).andExpect(jsonPath("$.approvedBy.id").value(811));
        assertThat(onHand(81)).isEqualTo(35);
        assertThat(onHand(82)).isNull();

        mvc.perform(get(DOCUMENTS + "/{id}", id).with(user("812").roles("STOREKEEPER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.toBranch.id").value(82));
        receive(draft, 48, "812").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED")).andExpect(jsonPath("$.receivedBy.id").value(812))
                .andExpect(jsonPath("$.receivedAt").isNotEmpty()).andExpect(jsonPath("$.hasDiscrepancy").value(true))
                .andExpect(jsonPath("$.items[0].receivedQuantity").value(48))
                .andExpect(jsonPath("$.items[0].difference").value(-2));
        assertThat(onHand(82)).isEqualTo(48);
        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory WHERE branch_id=81", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT cost_price FROM product WHERE id=81", Long.class)).isEqualTo(10000L);
        assertThat(jdbc.queryForList("""
                SELECT type || ':' || branch_id || ':' || delta_on_hand || ':' || created_by
                FROM inventory_transaction WHERE ref_type='STOCK_DOCUMENT' AND ref_id=? ORDER BY id
                """, String.class, id)).containsExactly("TRANSFER_OUT:81:-50:811", "TRANSFER_IN:82:48:812");

        receive(draft, 48, "812").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        assertThat(onHand(82)).isEqualTo(48);
    }

    @Test void rejectsWrongBranchAndExcessWithoutMutatingDestination() throws Exception {
        var draft = draft();
        long id = draft.get("id").asLong();
        receive(draft, 48, "812").andExpect(status().isConflict());
        approve(id).andExpect(status().isOk());
        receive(draft, 48, "813").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
        receive(draft, 48, "810").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
        receive(draft, 51, "812").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECEIVED_QUANTITY_EXCEEDED"));
        assertThat(onHand(82)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM stock_document WHERE id=?", String.class, id))
                .isEqualTo("IN_TRANSIT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction", Integer.class)).isEqualTo(1);
    }

    @Test void approvalRechecksAvailableStockAfterDraft() throws Exception {
        var draft = draft();
        long id = draft.get("id").asLong();
        jdbc.update("UPDATE inventory SET reserved=40 WHERE branch_id=81 AND product_id=81");
        approve(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        assertThat(onHand(81)).isEqualTo(85);
        assertThat(jdbc.queryForObject("SELECT status FROM stock_document WHERE id=?", String.class, id))
                .isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction", Integer.class)).isZero();
    }

    @Test void zeroReceivedRecordsFullShortageWithoutCreatingDestinationStock() throws Exception {
        var draft = draft();
        approve(draft.get("id").asLong()).andExpect(status().isOk());
        receive(draft, 0, "812").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED")).andExpect(jsonPath("$.items[0].difference").value(-50));
        assertThat(onHand(82)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction", Integer.class)).isEqualTo(1);
    }

    private com.fasterxml.jackson.databind.JsonNode draft() throws Exception {
        var body = Map.of("type", "TRANSFER", "branchId", 81, "toBranchId", 82,
                "items", List.of(Map.of("productId", 81, "quantity", 50)));
        var response = mvc.perform(post(DOCUMENTS).with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray());
    }

    private org.springframework.test.web.servlet.ResultActions approve(long id) throws Exception {
        return mvc.perform(post(DOCUMENTS + "/{id}/approve", id).with(user("811").roles("BRANCH_MANAGER")));
    }

    private org.springframework.test.web.servlet.ResultActions receive(com.fasterxml.jackson.databind.JsonNode draft,
            int quantity, String userId) throws Exception {
        long item = draft.get("items").get(0).get("id").asLong();
        var body = Map.of("items", List.of(Map.of("itemId", item, "receivedQuantity", quantity)));
        return mvc.perform(post(DOCUMENTS + "/{id}/receive", draft.get("id").asLong())
                .with(user(userId).roles("STOREKEEPER")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body)));
    }

    private Integer onHand(long branch) {
        return jdbc.query("SELECT on_hand FROM inventory WHERE branch_id=? AND product_id=81",
                (rs, row) -> rs.getInt(1), branch).stream().findFirst().orElse(null);
    }
}
