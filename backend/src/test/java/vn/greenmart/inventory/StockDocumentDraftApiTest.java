package vn.greenmart.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.domain.StockDocument;
import vn.greenmart.inventory.domain.StockDocumentItem;
import vn.greenmart.inventory.domain.StockDocumentStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.nullValue;
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
@Transactional
class StockDocumentDraftApiTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.forecast_user", () -> "forecast_ro");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired jakarta.persistence.EntityManager entityManager;

    @BeforeEach
    void fixtures() {
        jdbc.update("INSERT INTO branch(id,code,name) VALUES(81,'B81','Branch 81'),(82,'B82','Branch 82')");
        jdbc.update("INSERT INTO category(id,name) VALUES(81,'Milk')");
        jdbc.update("INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price) "
                + "VALUES(81,'MILK','Milk','hộp',81,29000,10000),(82,'OTHER','Other','hộp',81,10000,5000)");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,full_name,role) VALUES "
                + "(810,'maker','hash','Maker','STOREKEEPER'),(811,'manager','hash','Manager','BRANCH_MANAGER')");
        jdbc.update("INSERT INTO user_branch(user_id,branch_id) VALUES(810,81),(811,81)");
        jdbc.update("INSERT INTO inventory(branch_id,product_id,on_hand,reserved) VALUES(81,81,5,1)");
    }

    private Map<String, Object> request() {
        return Map.of("type", "IMPORT", "branchId", 81, "supplier", "Supplier",
                "note", "Original note", "items", List.of(Map.of("productId", 81, "quantity", 24, "unitCost", 23500)));
    }

    private long create() throws Exception {
        var result = mvc.perform(post("/api/v1/stock-documents").with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.createdBy.id").value(810))
                .andExpect(jsonPath("$.approvedAt", nullValue())).andExpect(jsonPath("$.receivedAt", nullValue()))
                .andExpect(jsonPath("$.items[0].quantity").value(24)).andExpect(jsonPath("$.items[0].unitCost").value(23500))
                .andExpect(jsonPath("$.totalCost", nullValue())).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).get("id").asLong();
    }

    private void assertStockUnchanged() {
        assertThat(jdbc.queryForMap("SELECT on_hand,reserved,version FROM inventory WHERE branch_id=81 AND product_id=81"))
                .containsEntry("on_hand", 5).containsEntry("reserved", 1).containsEntry("version", 0L);
        assertThat(jdbc.queryForObject("SELECT cost_price FROM product WHERE id=81", Long.class)).isEqualTo(10000L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction", Integer.class)).isZero();
    }

    @Test
    void createsAndReadsDraftWithoutInventoryOrCostEffects() throws Exception {
        long id = create();
        mvc.perform(get("/api/v1/stock-documents/{id}", id).with(user("811").roles("BRANCH_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.branch.id").value(81))
                .andExpect(jsonPath("$.createdBy.fullName").value("Maker"))
                .andExpect(jsonPath("$.totalCost").value(564000)).andExpect(jsonPath("$.itemCount").value(1))
                .andExpect(jsonPath("$.items[0].sku").value("MILK"));
        assertStockUnchanged();
    }

    @Test
    void updatesDraftItemsAndSupplier() throws Exception {
        long id = create();
        mvc.perform(put("/api/v1/stock-documents/{id}", id).with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"supplier":"New supplier","items":[{"productId":82,"quantity":3,"unitCost":5000}]}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.supplier").value("New supplier"))
                .andExpect(jsonPath("$.items[0].productId").value(82)).andExpect(jsonPath("$.note").value("Original note"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_document_item WHERE document_id=?", Integer.class, id)).isEqualTo(1);
        assertStockUnchanged();
    }

    @Test
    void cancelsDraftWithReasonInAuditAndPreservesOriginalNote() throws Exception {
        long id = create();
        mvc.perform(post("/api/v1/stock-documents/{id}/cancel", id).with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Wrong supplier\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.note").value("Original note"));
        assertThat(jdbc.queryForObject("SELECT detail->>'reason' FROM audit_log WHERE action='CANCEL_STOCK_DOCUMENT'", String.class))
                .isEqualTo("Wrong supplier");
        mvc.perform(post("/api/v1/stock-documents/{id}/cancel", id).with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Cancel again\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        assertStockUnchanged();
    }

    @Test
    void approvedDocumentCannotBeEditedOrCancelled() throws Exception {
        long id = create();
        jdbc.update("UPDATE stock_document SET status='APPROVED',approved_by=811,approved_at=created_at WHERE id=?", id);
        entityManager.clear(); // Fixture SQL bypasses JPA; the next load must read the database state.
        mvc.perform(put("/api/v1/stock-documents/{id}", id).with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATUS"))
                .andExpect(jsonPath("$.details.currentStatus").value("APPROVED"));
        mvc.perform(post("/api/v1/stock-documents/{id}/cancel", id).with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Too late\"}"))
                .andExpect(status().isConflict());
        assertStockUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"supplier\":\" \"}", "{\"type\":\"EXPORT\"}", "{\"toBranchId\":82}", "{\"items\":[]}",
            "{\"items\":[null]}", "{\"items\":[{\"productId\":81,\"quantity\":0,\"unitCost\":0}]}",
            "{\"items\":[{\"productId\":81,\"quantity\":100000,\"unitCost\":0}]}",
            "{\"items\":[{\"productId\":81,\"quantity\":1}]}",
            "{\"items\":[{\"productId\":81,\"quantity\":1,\"unitCost\":-1}]}",
            "{\"items\":[{\"productId\":81,\"quantity\":1,\"unitCost\":0,\"countedQuantity\":2}]}",
            "{\"items\":[{\"productId\":81,\"quantity\":1,\"unitCost\":0},{\"productId\":81,\"quantity\":1,\"unitCost\":0}]}",
            "{\"items\":[{\"productId\":81,\"quantity\":99999,\"unitCost\":9223372036854775807}]}"
    })
    void rejectsInvalidImportInputBeforeWriting(String override) throws Exception {
        var body = json.valueToTree(request());
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).setAll((com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(override));
        mvc.perform(post("/api/v1/stock-documents").with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_document", Integer.class)).isZero();
        assertStockUnchanged();
    }

    @ParameterizedTest
    @CsvSource({"CASHIER,810,403", "OWNER,810,403", "STOREKEEPER,unknown,401", "STOREKEEPER,999,401"})
    void rejectsWrongRoleOrUnusableActor(String role, String actor, int expected) throws Exception {
        mvc.perform(post("/api/v1/stock-documents").with(user(actor).roles(role))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request())))
                .andExpect(status().is(expected));
    }

    @Test
    void forbidsAnotherBranchAndAnonymousWrites() throws Exception {
        var body = new java.util.HashMap<>(request());
        body.put("branchId", 82);
        mvc.perform(post("/api/v1/stock-documents").with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
        mvc.perform(post("/api/v1/stock-documents").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingProductOnEditDoesNotReplaceTheOriginalLines() throws Exception {
        long id = create();
        mvc.perform(put("/api/v1/stock-documents/{id}", id).with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"items":[{"productId":999,"quantity":1,"unitCost":100}]}
                        """))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT product_id FROM stock_document_item WHERE document_id=?", Long.class, id)).isEqualTo(81L);
        assertStockUnchanged();
    }

    @Test
    void cannotReadEditOrCancelDocumentOutsideAssignedBranch() throws Exception {
        long id = create();
        jdbc.update("UPDATE user_branch SET branch_id=82 WHERE user_id=810");
        mvc.perform(get("/api/v1/stock-documents/{id}", id).with(user("810").roles("STOREKEEPER")))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/stock-documents/{id}", id).with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/stock-documents/{id}/cancel", id).with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Wrong branch\"}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT status FROM stock_document WHERE id=?", String.class, id)).isEqualTo("DRAFT");
        assertStockUnchanged();
    }

    @Test
    void rejectsInactiveActorAndMissingCancellationReason() throws Exception {
        long id = create();
        mvc.perform(post("/api/v1/stock-documents/{id}/cancel", id).with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" a \"}"))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE app_user SET is_active=false WHERE id=810");
        mvc.perform(post("/api/v1/stock-documents").with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request())))
                .andExpect(status().isUnauthorized());
        assertStockUnchanged();
    }

    @Test
    void approvalWritesStockCostLedgerAndCannotBeRepeated() throws Exception {
        long id = create();
        mvc.perform(post("/api/v1/stock-documents/{id}/approve", id).with(user("811").roles("BRANCH_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy.id").value(811)).andExpect(jsonPath("$.approvedAt").isNotEmpty());
        assertThat(jdbc.queryForMap("SELECT on_hand,reserved FROM inventory WHERE branch_id=81 AND product_id=81"))
                .containsEntry("on_hand", 29).containsEntry("reserved", 1);
        assertThat(jdbc.queryForObject("SELECT cost_price FROM product WHERE id=81", Long.class)).isEqualTo(21172L);
        assertThat(jdbc.queryForMap("SELECT type,delta_on_hand,delta_reserved,on_hand_after,reserved_after,ref_type,ref_id,created_by FROM inventory_transaction"))
                .containsEntry("type", "IMPORT").containsEntry("delta_on_hand", 24).containsEntry("delta_reserved", 0)
                .containsEntry("on_hand_after", 29).containsEntry("reserved_after", 1)
                .containsEntry("ref_type", "STOCK_DOCUMENT").containsEntry("ref_id", id).containsEntry("created_by", 811L);
        mvc.perform(post("/api/v1/stock-documents/{id}/approve", id).with(user("811").roles("BRANCH_MANAGER")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT user_id,entity,entity_id FROM audit_log WHERE action='APPROVE_STOCK_DOCUMENT'"))
                .containsEntry("user_id", 811L).containsEntry("entity", "stock_document").containsEntry("entity_id", Long.toString(id));
    }

    @ParameterizedTest
    @CsvSource({"20,30,11000,10600", "1,2,10001,10001", "1,1,10001,10001", "0,24,23500,23500"})
    void followsLeadersGlobalOnHandAndHalfUpExamples(int oldStock, int qty, long unitCost, long expected) throws Exception {
        jdbc.update("UPDATE inventory SET on_hand=0,reserved=0 WHERE branch_id=81 AND product_id=81");
        jdbc.update("INSERT INTO inventory(branch_id,product_id,on_hand,reserved) VALUES(82,81,?,?)", oldStock, oldStock);
        long id = create();
        jdbc.update("UPDATE stock_document_item SET quantity=?,unit_cost=? WHERE document_id=?", qty, unitCost, id);
        entityManager.clear();
        mvc.perform(post("/api/v1/stock-documents/{id}/approve", id).with(user("811").roles("BRANCH_MANAGER")))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT cost_price FROM product WHERE id=81", Long.class)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory WHERE branch_id=81 AND product_id=81", Integer.class)).isEqualTo(qty);
    }

    @Test
    void makerManagerCannotApproveOwnDocumentAndOtherRolesCannotApprove() throws Exception {
        long id = create();
        mvc.perform(post("/api/v1/stock-documents/{id}/approve", id).with(user("810").roles("STOREKEEPER")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"));
        jdbc.update("UPDATE stock_document SET created_by=811 WHERE id=?", id);
        entityManager.clear();
        mvc.perform(post("/api/v1/stock-documents/{id}/approve", id).with(user("811").roles("BRANCH_MANAGER")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"));
        for (String role : List.of("STOREKEEPER", "OWNER", "CASHIER")) {
            mvc.perform(post("/api/v1/stock-documents/{id}/approve", id).with(user("810").roles(role)))
                    .andExpect(status().isForbidden());
        }
        assertStockUnchanged();
    }

    @Test
    void createsMissingInventoryRowOnApproval() throws Exception {
        long id = create();
        jdbc.update("DELETE FROM inventory WHERE branch_id=81 AND product_id=81");
        mvc.perform(post("/api/v1/stock-documents/{id}/approve", id).with(user("811").roles("BRANCH_MANAGER")))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForMap("SELECT on_hand,reserved,version FROM inventory WHERE branch_id=81 AND product_id=81"))
                .containsEntry("on_hand", 24).containsEntry("reserved", 0).containsEntry("version", 1L);
        assertThat(jdbc.queryForObject("SELECT cost_price FROM product WHERE id=81", Long.class)).isEqualTo(23500L);
    }

    @Test
    void approvalChecksBranchAndCancelledStateBeforeStockWrites() throws Exception {
        long id = create();
        jdbc.update("UPDATE user_branch SET branch_id=82 WHERE user_id=811");
        mvc.perform(post("/api/v1/stock-documents/{id}/approve", id).with(user("811").roles("BRANCH_MANAGER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
        jdbc.update("UPDATE user_branch SET branch_id=81 WHERE user_id=811");
        mvc.perform(post("/api/v1/stock-documents/{id}/cancel", id).with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"No longer needed\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/stock-documents/{id}/approve", id).with(user("811").roles("BRANCH_MANAGER")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        assertStockUnchanged();
    }

    @Test
    void approvalGuardRejectsMakerWithoutApprovingOrChangingInventory() {
        var draft = StockDocument.importDraft(81L, 810L, "Supplier", null,
                List.of(StockDocumentItem.imported(81L, 24, 23500)));
        assertThatThrownBy(() -> draft.requireApprovableBy(810L))
                .isInstanceOfSatisfying(vn.greenmart.common.exception.AppException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getCode()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
                });
        assertThatCode(() -> draft.requireApprovableBy(811L)).doesNotThrowAnyException();
        assertThat(draft.getStatus()).isEqualTo(StockDocumentStatus.DRAFT);
        assertThat(draft.getApprovedAt()).isNull();
        assertStockUnchanged();
    }
}
