package vn.greenmart.inventory;

import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vn.greenmart.sales.service.PosIdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.concurrent.*;

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
class StockExportApiTest {
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

    @BeforeEach void fixtures() {
        jdbc.execute("TRUNCATE branch,category,app_user CASCADE");
        jdbc.update("INSERT INTO branch(id,code,name) VALUES(81,'B81','Branch 81'),(82,'B82','Branch 82')");
        jdbc.update("INSERT INTO category(id,name) VALUES(81,'Milk')");
        jdbc.update("INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price) "
                + "VALUES(81,'MILK','Milk','hop',81,29000,10000),(82,'OTHER','Other','hop',81,10000,5000)");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,full_name,role) VALUES "
                + "(810,'maker','hash','Maker','STOREKEEPER'),(811,'manager','hash','Manager','BRANCH_MANAGER'),"
                + "(812,'owner','hash','Owner','OWNER')");
        jdbc.update("INSERT INTO user_branch(user_id,branch_id) VALUES(810,81),(811,81)");
        jdbc.update("INSERT INTO inventory(branch_id,product_id,on_hand,reserved) VALUES(81,81,10,2),(81,82,10,0)");
    }
    private Map<String,Object> request(int qty, String reason, String note) {
        return Map.of("type","EXPORT","branchId",81,"exportReason",reason,"note",note,
                "items",List.of(Map.of("productId",81,"quantity",qty)));
    }
    private long create(int qty) throws Exception {
        var result = mvc.perform(post("/api/v1/stock-documents").with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request(qty,"DAMAGED","Broken seal"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.totalCost",nullValue())).andExpect(jsonPath("$.items[0].unitCost",nullValue())).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).get("id").asLong();
    }
    private org.springframework.test.web.servlet.ResultActions approve(long id) throws Exception {
        return mvc.perform(post("/api/v1/stock-documents/{id}/approve",id).with(user("811").roles("BRANCH_MANAGER")));
    }
    private void unchanged() {
        assertThat(jdbc.queryForMap("SELECT on_hand,reserved,version FROM inventory WHERE branch_id=81 AND product_id=81"))
                .containsEntry("on_hand",10).containsEntry("reserved",2).containsEntry("version",0L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT cost_price FROM product WHERE id=81",Long.class)).isEqualTo(10000L);
    }

    @ParameterizedTest @ValueSource(strings={"EXPIRED","DAMAGED","LOST","OTHER"})
    void draftPreservesReasonAndFullNoteWithoutStockEffects(String reason) throws Exception {
        String note="a".repeat(255);
        mvc.perform(post("/api/v1/stock-documents").with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request(3,reason,note))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.exportReason").value(reason))
                .andExpect(jsonPath("$.note").value(note));
        assertThat(jdbc.queryForMap("SELECT export_reason,reason FROM stock_document"))
                .containsEntry("export_reason",reason).containsEntry("reason",note);
        unchanged();
    }
    @ParameterizedTest @ValueSource(strings={
            "{\"exportReason\":null}","{\"exportReason\":\"\"}","{\"exportReason\":\"UNKNOWN\"}",
            "{\"exportReason\":\"OTHER\",\"note\":\"  \"}","{\"exportReason\":\"OTHER\",\"note\":null}",
            "{\"supplier\":\"Supplier\"}","{\"toBranchId\":82}","{\"items\":[]}",
            "{\"items\":[{\"productId\":81,\"quantity\":0}]}",
            "{\"items\":[{\"productId\":81,\"quantity\":100000}]}",
            "{\"items\":[{\"productId\":81,\"quantity\":1,\"unitCost\":100}]}",
            "{\"items\":[{\"productId\":81,\"quantity\":1,\"countedQuantity\":1}]}",
            "{\"items\":[{\"productId\":81,\"quantity\":1},{\"productId\":81,\"quantity\":1}]}"
    })
    void rejectsInvalidExportBeforeSaving(String override) throws Exception {
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(request(3,"DAMAGED","Broken seal"));
        body.setAll((com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(override));
        mvc.perform(post("/api/v1/stock-documents").with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_document",Integer.class)).isZero();
        unchanged();
    }
    @Test void approvalDecreasesOnlyOnHandAndSnapshotsHistoricalCost() throws Exception {
        long id=create(3);
        approve(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.totalCost").value(30000)).andExpect(jsonPath("$.approvedBy.id").value(811))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty()).andExpect(jsonPath("$.exportReason").value("DAMAGED"))
                .andExpect(jsonPath("$.items[0].unitCost",nullValue()));
        assertThat(jdbc.queryForMap("SELECT on_hand,reserved,version FROM inventory WHERE branch_id=81 AND product_id=81"))
                .containsEntry("on_hand",7).containsEntry("reserved",2).containsEntry("version",1L);
        assertThat(jdbc.queryForMap("SELECT type,delta_on_hand,delta_reserved,on_hand_after,reserved_after,ref_type,ref_id,created_by FROM inventory_transaction"))
                .containsEntry("type","EXPORT").containsEntry("delta_on_hand",-3).containsEntry("delta_reserved",0)
                .containsEntry("on_hand_after",7).containsEntry("reserved_after",2)
                .containsEntry("ref_type","STOCK_DOCUMENT").containsEntry("ref_id",id).containsEntry("created_by",811L);
        assertThat(jdbc.queryForObject("SELECT cost_price FROM product WHERE id=81",Long.class)).isEqualTo(10000L);
        assertThat(jdbc.queryForObject("SELECT export_cost_price FROM stock_document_item WHERE document_id=?",Long.class,id)).isEqualTo(10000L);
        jdbc.update("UPDATE product SET cost_price=90000 WHERE id=81");
        mvc.perform(get("/api/v1/stock-documents/{id}",id).with(user("812").roles("OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCost").value(30000));
        mvc.perform(get("/api/v1/stock-documents/{id}",id).with(user("810").roles("STOREKEEPER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCost",nullValue()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='APPROVE_STOCK_DOCUMENT'",Integer.class)).isEqualTo(1);
    }
    @Test void createCannotExceedAvailableAndApprovalRechecksAfterReservation() throws Exception {
        mvc.perform(post("/api/v1/stock-documents").with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request(10,"EXPIRED","Expired"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.details.lines[0].available").value(8));
        jdbc.update("UPDATE inventory SET reserved=0 WHERE branch_id=81 AND product_id=81");
        long id=create(10);
        jdbc.update("UPDATE inventory SET reserved=2 WHERE branch_id=81 AND product_id=81");
        approve(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.details.lines[0].available").value(8));
        assertThat(jdbc.queryForObject("SELECT status FROM stock_document WHERE id=?",String.class,id)).isEqualTo("DRAFT");
        unchanged();
    }
    @Test void updateReplacesDraftLinesAndKeepsReasonWhenOmitted() throws Exception {
        long id=create(3);
        mvc.perform(put("/api/v1/stock-documents/{id}",id).with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"items":[{"productId":82,"quantity":2}],"note":"Updated note"}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.exportReason").value("DAMAGED"))
                .andExpect(jsonPath("$.note").value("Updated note")).andExpect(jsonPath("$.items[0].productId").value(82));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_document_item WHERE document_id=?",Integer.class,id)).isEqualTo(1);
        unchanged();
    }
    @Test void repeatedApprovalEditAndCancelOfApprovedAreRejected() throws Exception {
        long id=create(3); approve(id).andExpect(status().isOk());
        approve(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        mvc.perform(put("/api/v1/stock-documents/{id}",id).with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request(1,"LOST","Missing"))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/stock-documents/{id}/cancel",id).with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Wrong document\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction",Integer.class)).isEqualTo(1);
    }
    @Test void cancellationDoesNotExportAndCannotThenApprove() throws Exception {
        long id=create(3);
        mvc.perform(post("/api/v1/stock-documents/{id}/cancel",id).with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Wrong document\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        approve(id).andExpect(status().isConflict()); unchanged();
    }
    @Test void selfApprovalWrongRoleAndBranchAreForbidden() throws Exception {
        var result=mvc.perform(post("/api/v1/stock-documents").with(user("811").roles("BRANCH_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request(3,"DAMAGED","Broken"))))
                .andExpect(status().isCreated()).andReturn();
        long own=json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        approve(own).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"));
        long id=create(3);
        mvc.perform(post("/api/v1/stock-documents/{id}/approve",id).with(user("812").roles("OWNER")))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE user_branch SET branch_id=82 WHERE user_id=811");
        approve(id).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
        mvc.perform(post("/api/v1/stock-documents").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(request(3,"DAMAGED","Broken"))))
                .andExpect(status().isUnauthorized());
        unchanged();
    }
    @Test void failingSecondLedgerInsertRollsBackEverything() throws Exception {
        long id=create(3);
        mvc.perform(put("/api/v1/stock-documents/{id}",id).with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"items":[{"productId":81,"quantity":3},{"productId":82,"quantity":2}]}
                        """))
                .andExpect(status().isOk());
        jdbc.execute("ALTER TABLE inventory_transaction ADD CONSTRAINT test_reject_export CHECK(product_id<>82)");
        try {
            approve(id).andExpect(status().isConflict()); unchanged();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_document_item WHERE export_cost_price IS NOT NULL",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='APPROVE_STOCK_DOCUMENT'",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM stock_document WHERE id=?",String.class,id)).isEqualTo("DRAFT");
            assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory WHERE branch_id=81 AND product_id=82",Integer.class)).isEqualTo(10);
        } finally { jdbc.execute("ALTER TABLE inventory_transaction DROP CONSTRAINT test_reject_export"); }
    }
    @Test void insufficientSecondLineRollsBackEarlierExport() throws Exception {
        long id=create(3);
        mvc.perform(put("/api/v1/stock-documents/{id}",id).with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"items":[{"productId":82,"quantity":2},{"productId":81,"quantity":3}]}
                        """))
                .andExpect(status().isOk());
        jdbc.update("UPDATE inventory SET reserved=10 WHERE branch_id=81 AND product_id=82");
        approve(id).andExpect(status().isConflict()).andExpect(jsonPath("$.details.lines[0].productId").value(82));
        unchanged();
        assertThat(jdbc.queryForObject("SELECT status FROM stock_document WHERE id=?",String.class,id)).isEqualTo("DRAFT");
    }
    @Test void concurrentApprovalsCannotDoubleExportOrConsumeReservations() throws Exception {
        long a=create(5), b=create(5);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Integer> first=()->{ start.await(10,TimeUnit.SECONDS); return approve(a).andReturn().getResponse().getStatus(); };
            Callable<Integer> second=()->{ start.await(10,TimeUnit.SECONDS); return approve(b).andReturn().getResponse().getStatus(); };
            var x=pool.submit(first); var y=pool.submit(second); start.countDown();
            assertThat(List.of(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForMap("SELECT on_hand,reserved FROM inventory WHERE branch_id=81 AND product_id=81"))
                .containsEntry("on_hand",5).containsEntry("reserved",2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction",Integer.class)).isEqualTo(1);
    }
    @Test void overflowingValueDoesNotChangeStockOrApproval() throws Exception {
        long id=create(3);
        jdbc.update("UPDATE product SET cost_price=? WHERE id=81",Long.MAX_VALUE);
        approve(id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory WHERE branch_id=81 AND product_id=81",Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM stock_document WHERE id=?",String.class,id)).isEqualTo("DRAFT");
    }
    @Test void sameDocumentConcurrentApprovalExportsOnlyOnce() throws Exception {
        long id=create(3);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Integer> action=()->{
                if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return approve(id).andReturn().getResponse().getStatus();
            };
            var a=pool.submit(action); var b=pool.submit(action); start.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory WHERE branch_id=81 AND product_id=81",Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction",Integer.class)).isEqualTo(1);
    }
    @Test void grandTotalOverflowRollsBackBothLines() throws Exception {
        long id=create(1);
        mvc.perform(put("/api/v1/stock-documents/{id}",id).with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"items":[{"productId":81,"quantity":1},{"productId":82,"quantity":1}]}
                        """))
                .andExpect(status().isOk());
        jdbc.update("UPDATE product SET cost_price=? WHERE id IN(81,82)",Long.MAX_VALUE/2+1);
        approve(id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(jdbc.queryForList("SELECT on_hand FROM inventory ORDER BY product_id",Integer.class)).containsExactly(10,10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM stock_document WHERE id=?",String.class,id)).isEqualTo("DRAFT");
    }
    @Test void exportAtZeroCostIsValidAndHasZeroLoss() throws Exception {
        long id=create(3); jdbc.update("UPDATE product SET cost_price=0 WHERE id=81");
        approve(id).andExpect(status().isOk()).andExpect(jsonPath("$.totalCost").value(0));
    }
}
