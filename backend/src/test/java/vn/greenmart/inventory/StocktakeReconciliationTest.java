package vn.greenmart.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.integration.channel.ChannelSignatures;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.data.redis.autoconfigure.RedisAutoConfiguration,org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "management.endpoint.health.group.readiness.include=readinessState,db",
        "greenmart.outbox.enabled=false", "greenmart.order.expiry.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class StocktakeReconciliationTest {
    @MockitoBean vn.greenmart.sales.service.PosIdempotencyService posIdempotencyService;
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl); r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("spring.flyway.placeholders.forecast_user", () -> "forecast_ro");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired ChannelSignatures signatures;
    private static final String DOCS = "/api/v1/stock-documents";
    private long orderId;

    @BeforeEach void fixtures() {
        jdbc.execute("TRUNCATE branch,category,app_user,customer CASCADE");
        jdbc.execute("TRUNCATE audit_log,outbox_event");
        jdbc.update("INSERT INTO branch(id,code,name) VALUES(421,'B421','Branch'),(422,'B422','Other')");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,full_name,role) VALUES(4211,'maker421','hash','Maker','STOREKEEPER'),(4212,'manager421','hash','Manager','BRANCH_MANAGER'),(4213,'other421','hash','Other','BRANCH_MANAGER')");
        jdbc.update("INSERT INTO user_branch(user_id,branch_id) VALUES(4211,421),(4212,421),(4213,422)");
        jdbc.update("INSERT INTO category(id,name) VALUES(421,'Test')");
        jdbc.update("INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price) VALUES(421,'SKU421','Item','hop',421,100,50)");
        jdbc.update("INSERT INTO inventory(branch_id,product_id,on_hand,reserved) VALUES(421,421,3,3)");
        jdbc.update("INSERT INTO sales_channel(code,name,webhook_secret) VALUES('WEB','Web','test-only-421')");
        jdbc.update("INSERT INTO channel_branch(channel_id,branch_id) SELECT id,421 FROM sales_channel WHERE code='WEB'");
        jdbc.update("INSERT INTO channel_product(channel_id,product_id,external_sku) SELECT id,421,'WEB421' FROM sales_channel WHERE code='WEB'");
        orderId = jdbc.queryForObject("INSERT INTO sales_order(code,channel_id,external_order_id,branch_id,status,subtotal,total,reserved_until) SELECT 'ORDER421',id,'ORDER421',421,'RESERVED',300,300,NOW()+INTERVAL '15 minutes' FROM sales_channel WHERE code='WEB' RETURNING id", Long.class);
        jdbc.update("INSERT INTO order_item(order_id,product_id,quantity,unit_price,line_total) VALUES(?,421,3,100,300)", orderId);
    }
    private long draft(String body, String actor, String role) throws Exception {
        return json.readTree(mvc.perform(post(DOCS).with(user(actor).roles(role)).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asLong();
    }
    private long counted(int quantity) throws Exception {
        long id = draft("{\"type\":\"ADJUST\",\"branchId\":421,\"items\":[{\"productId\":421}]}", "4211", "STOREKEEPER");
        counts(id, quantity).andExpect(status().isOk()); return id;
    }
    private ResultActions counts(long id, int quantity) throws Exception {
        return mvc.perform(put(DOCS + "/{id}", id).with(user("4211").roles("STOREKEEPER")).contentType("application/json")
                .content("{\"items\":[{\"productId\":421,\"countedQuantity\":" + quantity + "}]}"));
    }
    private ResultActions inspect(long id) throws Exception {
        return mvc.perform(get(DOCS + "/{id}/reconciliation", id).with(user("4211").roles("STOREKEEPER")));
    }
    private ResultActions approve(long id) throws Exception {
        return mvc.perform(post(DOCS + "/{id}/approve", id).with(user("4212").roles("BRANCH_MANAGER")));
    }
    private ResultActions cancelOrder() throws Exception {
        return mvc.perform(post("/api/v1/orders/{id}/cancel", orderId).with(user("4212").roles("BRANCH_MANAGER"))
                .contentType("application/json").content("{\"reason\":\"CUSTOMER_REQUEST\"}"));
    }
    private int stock(String column) { return jdbc.queryForObject("SELECT " + column + " FROM inventory WHERE branch_id=421 AND product_id=421", Integer.class); }

    @Test void blockedApprovalKeepsCountDiscrepancyAuditAndReservationReferences() throws Exception {
        long id = counted(2);
        inspect(id).andExpect(status().isOk()).andExpect(jsonPath("$.inventoryReady").value(false))
                .andExpect(jsonPath("$.lines[0].snapshotQuantity").value(3)).andExpect(jsonPath("$.lines[0].countedQuantity").value(2))
                .andExpect(jsonPath("$.lines[0].difference").value(-1)).andExpect(jsonPath("$.lines[0].projectedOnHand").value(2))
                .andExpect(jsonPath("$.lines[0].shortfall").value(1)).andExpect(jsonPath("$.lines[0].reservationGap").value(0))
                .andExpect(jsonPath("$.lines[0].reservationOrders[0].orderId").value(orderId));
        approve(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.details.reconciliationPath").value(DOCS + "/" + id + "/reconciliation"));
        assertThat(stock("on_hand")).isEqualTo(3); assertThat(stock("reserved")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM stock_document WHERE id=?", String.class, id)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='RECORD_STOCK_COUNT' AND entity_id=?", Long.class, Long.toString(id))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment", Long.class)).isZero();
    }
    @ParameterizedTest @ValueSource(booleans = {false,true})
    void explicitCancellationResolvesReservationsAndPaidOrderUsesExistingRefundFlow(boolean paid) throws Exception {
        if (paid) {
            jdbc.update("UPDATE sales_order SET status='CONFIRMED' WHERE id=?", orderId);
            jdbc.update("INSERT INTO payment(order_id,method,amount,status,provider_ref) VALUES(?,'QR',300,'PAID','REF421')", orderId);
        }
        long id = counted(2); approve(id).andExpect(status().isConflict());
        cancelOrder().andExpect(status().isOk());
        inspect(id).andExpect(status().isOk()).andExpect(jsonPath("$.inventoryReady").value(true));
        approve(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(stock("on_hand")).isEqualTo(2); assertThat(stock("reserved")).isZero();
        if (paid) assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE order_id=?", String.class, orderId)).isEqualTo("REFUNDED");
    }
    @Test void realImportResolvesShortfallWithoutDiscardingInterveningGoodsOrCancellingOrders() throws Exception {
        long id = counted(2);
        long receipt = draft("{\"type\":\"IMPORT\",\"branchId\":421,\"supplier\":\"Supplier\",\"items\":[{\"productId\":421,\"quantity\":1,\"unitCost\":50}]}", "4211", "STOREKEEPER");
        approve(receipt).andExpect(status().isOk());
        inspect(id).andExpect(status().isOk()).andExpect(jsonPath("$.inventoryReady").value(true))
                .andExpect(jsonPath("$.lines[0].countedQuantity").value(2)).andExpect(jsonPath("$.lines[0].projectedOnHand").value(3));
        approve(id).andExpect(status().isOk()); assertThat(stock("on_hand")).isEqualTo(3); assertThat(stock("reserved")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM sales_order WHERE id=?", String.class, orderId)).isEqualTo("RESERVED");
    }
    @Test void rolesBranchesMissingCountsAndNonDraftOrNonAdjustmentDocumentsAreChecked() throws Exception {
        long id = draft("{\"type\":\"ADJUST\",\"branchId\":421,\"items\":[{\"productId\":421}]}", "4211", "STOREKEEPER");
        inspect(id).andExpect(status().isOk()).andExpect(jsonPath("$.inventoryReady").value(false))
                .andExpect(jsonPath("$.lines[0].projectedOnHand").isEmpty());
        mvc.perform(get(DOCS + "/{id}/reconciliation", id).with(user("4213").roles("BRANCH_MANAGER"))).andExpect(status().isForbidden());
        mvc.perform(get(DOCS + "/{id}/reconciliation", id).with(user("4211").roles("CASHIER"))).andExpect(status().isForbidden());
        mvc.perform(get(DOCS + "/999999/reconciliation").with(user("4211").roles("STOREKEEPER"))).andExpect(status().isNotFound());
        approve(id).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("COUNTED_QUANTITY_REQUIRED"));
        long own = draft("{\"type\":\"ADJUST\",\"branchId\":421,\"items\":[{\"productId\":421}]}", "4212", "BRANCH_MANAGER");
        approve(own).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"));
        long receipt = draft("{\"type\":\"IMPORT\",\"branchId\":421,\"supplier\":\"Supplier\",\"items\":[{\"productId\":421,\"quantity\":1,\"unitCost\":50}]}", "4211", "STOREKEEPER");
        inspect(receipt).andExpect(status().isConflict());
        counts(id,3).andExpect(status().isOk()); approve(id).andExpect(status().isOk()); inspect(id).andExpect(status().isConflict());
    }
    @Test void reservationReferencesAreBoundedButTotalsIncludeEveryOrderAndRepeatedSkuLine() throws Exception {
        jdbc.update("UPDATE order_item SET quantity=1,line_total=100 WHERE order_id=?", orderId);
        jdbc.update("UPDATE sales_order SET subtotal=200,total=200 WHERE id=?", orderId);
        jdbc.update("INSERT INTO order_item(order_id,product_id,quantity,unit_price,line_total) VALUES(?,421,1,100,100)", orderId);
        for (int n=0;n<24;n++) {
            long id=jdbc.queryForObject("INSERT INTO sales_order(code,channel_id,external_order_id,branch_id,status,subtotal,total) SELECT ?,id,?,421,'RESERVED',100,100 FROM sales_channel WHERE code='WEB' RETURNING id",Long.class,"R421-"+n,"R421-"+n);
            jdbc.update("INSERT INTO order_item(order_id,product_id,quantity,unit_price,line_total) VALUES(?,421,1,100,100)",id);
        }
        jdbc.update("UPDATE inventory SET on_hand=30,reserved=26 WHERE branch_id=421 AND product_id=421");
        long id=counted(1);
        inspect(id).andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].reservationOrderCount").value(25))
                .andExpect(jsonPath("$.lines[0].reservationOrders.length()").value(20))
                .andExpect(jsonPath("$.lines[0].reservationOrders[0].quantity").value(2)).andExpect(jsonPath("$.lines[0].reservationGap").value(0));
    }
    @Test void auditFailureRollsBackCountEditsWithoutWeakeningAppendOnlyAudit() throws Exception {
        long id=counted(2);
        jdbc.execute("CREATE FUNCTION issue421_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test-only audit failure'; END $$");
        jdbc.execute("CREATE TRIGGER issue421_fault BEFORE INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION issue421_fault()");
        try { counts(id,3).andExpect(status().is5xxServerError()); }
        finally { jdbc.execute("DROP FUNCTION issue421_fault() CASCADE"); }
        inspect(id).andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].countedQuantity").value(2));
    }
    @Test void currentProjectionUsesLongArithmeticAndReportsUnattributedReservations() throws Exception {
        jdbc.update("UPDATE inventory SET on_hand=2147483647,reserved=0 WHERE branch_id=421 AND product_id=421");
        long id=counted(Integer.MAX_VALUE);
        jdbc.update("UPDATE inventory SET on_hand=2147483647-1 WHERE branch_id=421 AND product_id=421");
        inspect(id).andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].reservationGap").value(-3));
        counts(id,0).andExpect(status().isOk());
        inspect(id).andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].projectedOnHand").value(-1))
                .andExpect(jsonPath("$.lines[0].shortfall").value(1)).andExpect(jsonPath("$.inventoryReady").value(false));
    }
    @Test void projectedStockAboveIntegerLimitIsRejectedWithoutWriting() throws Exception {
        long id=counted(Integer.MAX_VALUE);
        jdbc.update("UPDATE inventory SET on_hand=4 WHERE branch_id=421 AND product_id=421");
        inspect(id).andExpect(status().isOk()).andExpect(jsonPath("$.inventoryReady").value(false))
                .andExpect(jsonPath("$.lines[0].projectedOnHand").value(2147483648L));
        approve(id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(stock("on_hand")).isEqualTo(4);
    }
    @Test void approvalAuditFailureRollsBackStockAndOutboxButKeepsEarlierCountAudit() throws Exception {
        long id=counted(4);
        jdbc.execute("CREATE FUNCTION issue421_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test-only audit failure'; END $$");
        jdbc.execute("CREATE TRIGGER issue421_fault BEFORE INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION issue421_fault()");
        try { approve(id).andExpect(status().is5xxServerError()); }
        finally { jdbc.execute("DROP FUNCTION issue421_fault() CASCADE"); }
        assertThat(stock("on_hand")).isEqualTo(3); assertThat(stock("reserved")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='RECORD_STOCK_COUNT'",Long.class)).isEqualTo(1);
        inspect(id).andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].countedQuantity").value(4));
    }
    @Test void concurrentReservationCannotBreakInvariantOrUseAStaleApprovalPrecheck() throws Exception {
        jdbc.update("UPDATE inventory SET reserved=2 WHERE branch_id=421 AND product_id=421");
        jdbc.update("UPDATE order_item SET quantity=2,line_total=200 WHERE order_id=?",orderId);
        jdbc.update("UPDATE sales_order SET subtotal=200,total=200 WHERE id=?",orderId);
        long id=counted(2);
        String body="{\"externalOrderId\":\"RACE421\",\"orderedAt\":\""+Instant.now()+"\",\"customer\":{\"name\":\"Race\",\"phone\":\"0900000421\"},\"items\":[{\"externalSku\":\"WEB421\",\"quantity\":1,\"unitPrice\":100}],\"paymentMethod\":\"ONLINE\"}";
        String signature=signatures.sign("WEB",body.getBytes(StandardCharsets.UTF_8));
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var approval=pool.submit(()->{start.await();return approve(id).andReturn().getResponse().getStatus();});
            var reserve=pool.submit(()->{start.await();return mvc.perform(post("/api/v1/integrations/WEB/orders").header("X-Signature",signature).contentType("application/json").content(body)).andReturn().getResponse().getStatus();});
            start.countDown(); int a=approval.get(20,TimeUnit.SECONDS), r=reserve.get(20,TimeUnit.SECONDS);
            assertThat(List.of(a,r)).isIn(List.of(200,409),List.of(409,202));
        }
        assertThat(stock("reserved")).isLessThanOrEqualTo(stock("on_hand"));
    }
}
