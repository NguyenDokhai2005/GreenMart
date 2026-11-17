package vn.greenmart.inventory;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.sales.service.PosIdempotencyService;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "management.endpoint.health.group.readiness.include=readinessState,db",
        "greenmart.outbox.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class StockAdjustmentApiTest {
    private static final String DOCUMENTS = "/api/v1/stock-documents";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.forecast_user", () -> "forecast_ro");
    }

    @MockitoBean PosIdempotencyService posIdempotencyService;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.execute("TRUNCATE branch,category,app_user CASCADE");
        jdbc.update("INSERT INTO branch(id,code,name) VALUES (81,'GM-001','Kho 1'),(82,'GM-002','Kho 2')");
        jdbc.update("INSERT INTO category(id,name) VALUES(81,'Milk')");
        jdbc.update("""
                INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price) VALUES
                (81,'MILK','Milk','hop',81,29000,10000),
                (82,'TEA','Tea','hop',81,21000,7000),
                (83,'COFFEE','Coffee','hop',81,55000,20000)
                """);
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,full_name,role) VALUES
                (810,'maker','hash','Maker','STOREKEEPER'),
                (811,'manager','hash','Manager','BRANCH_MANAGER'),
                (812,'outsider','hash','Outsider','BRANCH_MANAGER')
                """);
        jdbc.update("INSERT INTO user_branch(user_id,branch_id) VALUES(810,81),(811,81),(812,82)");
        jdbc.update("""
                INSERT INTO inventory(branch_id,product_id,on_hand,reserved,reorder_point)
                VALUES(81,81,10,2,NULL),(81,82,7,5,1)
                """);
    }

    @Test
    void preservesInterveningStockChangesAndRecordsApprovalLedgerAndEvents() throws Exception {
        long id = draft("810", "STOREKEEPER");
        jdbc.update("UPDATE inventory SET on_hand=8, version=1 WHERE branch_id=81 AND product_id=81");
        saveCounts(id, """
                {"items":[{"productId":81,"countedQuantity":13},{"productId":82,"countedQuantity":6},
                          {"productId":83,"countedQuantity":0}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(10))
                .andExpect(jsonPath("$.items[0].difference").value(3))
                .andExpect(jsonPath("$.items[1].difference").value(-1))
                .andExpect(jsonPath("$.items[2].difference").value(0));
        assertThat(onHand(81)).isEqualTo(8);

        approve(id, "811", "BRANCH_MANAGER")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy.id").value(811)).andExpect(jsonPath("$.approvedAt").isNotEmpty());
        assertThat(onHand(81)).isEqualTo(11);
        assertThat(onHand(82)).isEqualTo(6);
        assertThat(onHand(83)).isNull();
        assertThat(jdbc.queryForList("""
                SELECT delta_on_hand FROM inventory_transaction
                WHERE type='ADJUST' AND ref_type='STOCK_DOCUMENT' AND ref_id=? AND created_by=811
                ORDER BY product_id
                """, Integer.class, id)).containsExactly(3, -1, 0);
        assertThat(jdbc.queryForList("""
                SELECT reserved_after FROM inventory_transaction WHERE ref_type='STOCK_DOCUMENT' AND ref_id=?
                ORDER BY product_id
                """, Integer.class, id)).containsExactly(2, 5, 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type='stock.changed'",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT severity FROM stock_alert WHERE branch_id=81 AND product_id=82 AND closed_at IS NULL
                """, String.class)).isEqualTo("LOW");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                WHERE action='APPROVE_STOCK_DOCUMENT' AND entity_id=? AND user_id=811
                """, Integer.class, Long.toString(id))).isEqualTo(1);

        approve(id, "811", "BRANCH_MANAGER").andExpect(status().isConflict());
        assertThat(onHand(81)).isEqualTo(11);
    }

    @Test
    void rollsBackEveryLineWhenAdjustmentWouldConsumeReservedStock() throws Exception {
        long id = draft("810", "STOREKEEPER");
        saveCounts(id, """
                {"items":[{"productId":81,"countedQuantity":13},{"productId":82,"countedQuantity":3},
                          {"productId":83,"countedQuantity":2}]}
                """).andExpect(status().isOk());

        approve(id, "811", "BRANCH_MANAGER").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        assertThat(onHand(81)).isEqualTo(10);
        assertThat(onHand(82)).isEqualTo(7);
        assertThat(onHand(83)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction WHERE ref_id=?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isZero();
        mvc.perform(get(DOCUMENTS + "/{id}", id).with(user("810").roles("STOREKEEPER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void refusesInvalidCountsAndApprovalUntilEveryLineIsCounted() throws Exception {
        long id = draft("810", "STOREKEEPER");
        saveCounts(id, """
                {"items":[{"productId":81,"countedQuantity":-1}]}
                """).andExpect(status().isBadRequest());
        saveCounts(id, """
                {"items":[{"productId":81,"quantity":9,"countedQuantity":2}]}
                """).andExpect(status().isBadRequest());
        saveCounts(id, """
                {"items":[{"productId":84,"countedQuantity":0}]}
                """).andExpect(status().isBadRequest());
        saveCounts(id, """
                {"items":[{"productId":81,"countedQuantity":10}]}
                """).andExpect(status().isOk());

        approve(id, "810", "STOREKEEPER").andExpect(status().isForbidden());
        approve(id, "812", "BRANCH_MANAGER").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
        approve(id, "811", "BRANCH_MANAGER").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COUNTED_QUANTITY_REQUIRED"));

        long managerDraft = draft("811", "BRANCH_MANAGER");
        approve(managerDraft, "811", "BRANCH_MANAGER").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"));
    }

    @Test
    void createsInventoryForAProductCountedAboveASnapshotOfZero() throws Exception {
        long id = draft("810", "STOREKEEPER");
        saveCounts(id, """
                {"items":[{"productId":81,"countedQuantity":10},{"productId":82,"countedQuantity":7},
                          {"productId":83,"countedQuantity":3}]}
                """).andExpect(status().isOk());
        approve(id, "811", "BRANCH_MANAGER").andExpect(status().isOk());
        assertThat(onHand(83)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT on_hand_after FROM inventory_transaction WHERE ref_id=? AND product_id=83
                """, Integer.class, id)).isEqualTo(3);
    }

    @Test
    void snapshotsWholeBranchAndAcceptsCountsInBatches() throws Exception {
        jdbc.update("""
                WITH added_products AS (
                    INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price)
                    SELECT id, 'COUNT-' || id, 'Count item ' || id, 'cai', 81, 100, 50
                    FROM generate_series(100,599) AS ids(id)
                    RETURNING id
                )
                INSERT INTO inventory(branch_id,product_id,on_hand,reserved)
                SELECT 81,id,0,0 FROM added_products
                """);
        jdbc.update("INSERT INTO inventory(branch_id,product_id,on_hand,reserved) VALUES(82,83,20,0)");

        var created = mvc.perform(post(DOCUMENTS).with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"ADJUST","branchId":81,"allProducts":true}
                                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.itemCount").value(502))
                .andExpect(jsonPath("$.items.length()").value(502)).andReturn();
        var document = json.readTree(created.getResponse().getContentAsByteArray());
        long id = document.path("id").asLong();
        var snapshot = document.path("items");
        assertThat(snapshot.get(0).path("quantity").asInt()).isEqualTo(10);
        assertThat(snapshot.get(1).path("quantity").asInt()).isEqualTo(7);
        assertThat(snapshot.get(2).path("productId").asLong()).isEqualTo(100);

        var first = json.createObjectNode();
        var firstItems = first.putArray("items");
        var remaining = json.createObjectNode();
        var remainingItems = remaining.putArray("items");
        for (int index = 0; index < snapshot.size(); index++) {
            var item = snapshot.get(index);
            var batch = index < 500 ? firstItems : remainingItems;
            batch.addObject().put("productId", item.path("productId").asLong())
                    .put("countedQuantity", item.path("quantity").asInt() + (index == 0 ? 1 : 0));
        }
        saveCounts(id, first.toString()).andExpect(status().isOk());
        approve(id, "811", "BRANCH_MANAGER").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COUNTED_QUANTITY_REQUIRED"));
        saveCounts(id, remaining.toString()).andExpect(status().isOk());
        approve(id, "811", "BRANCH_MANAGER").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(onHand(81)).isEqualTo(11);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory_transaction WHERE ref_type='STOCK_DOCUMENT' AND ref_id=? AND type='ADJUST'
                """, Integer.class, id)).isEqualTo(502);
    }

    @Test
    void rejectsConflictingOrMissingCreateInputs() throws Exception {
        for (String payload : List.of(
                """
                {"type":"ADJUST","branchId":81}
                """,
                """
                {"type":"ADJUST","branchId":81,"allProducts":true,"items":[{"productId":81}]}
                """,
                """
                {"type":"IMPORT","branchId":81,"allProducts":true,"supplier":"Supplier"}
                """)) {
            mvc.perform(post(DOCUMENTS).with(user("810").roles("STOREKEEPER"))
                            .contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void concurrentApprovalsApplyAnAdjustmentExactlyOnce() throws Exception {
        long id = draft("810", "STOREKEEPER");
        saveCounts(id, """
                {"items":[{"productId":81,"countedQuantity":13},{"productId":82,"countedQuantity":7},
                          {"productId":83,"countedQuantity":0}]}
                """).andExpect(status().isOk());

        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Integer> approveOnce = () -> {
                start.await();
                return approve(id, "811", "BRANCH_MANAGER").andReturn().getResponse().getStatus();
            };
            var first = executor.submit(approveOnce);
            var second = executor.submit(approveOnce);
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }

        assertThat(onHand(81)).isEqualTo(13);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory_transaction WHERE ref_type='STOCK_DOCUMENT' AND ref_id=? AND type='ADJUST'
                """, Integer.class, id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type='stock.changed'",
                Integer.class)).isEqualTo(1);
    }

    private long draft(String actor, String role) throws Exception {
        var result = mvc.perform(post(DOCUMENTS).with(user(actor).roles(role)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"ADJUST","branchId":81,
                                 "items":[{"productId":81},{"productId":82},{"productId":83}]}
                                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.items[0].quantity").value(10))
                .andExpect(jsonPath("$.items[2].quantity").value(0)).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).path("id").asLong();
    }

    private ResultActions saveCounts(long id, String body) throws Exception {
        return mvc.perform(put(DOCUMENTS + "/{id}", id).with(user("810").roles("STOREKEEPER"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions approve(long id, String actor, String role) throws Exception {
        return mvc.perform(post(DOCUMENTS + "/{id}/approve", id).with(user(actor).roles(role)));
    }

    private Integer onHand(long productId) {
        return jdbc.queryForList("SELECT on_hand FROM inventory WHERE branch_id=81 AND product_id=?", Integer.class,
                productId).stream().findFirst().orElse(null);
    }
}
