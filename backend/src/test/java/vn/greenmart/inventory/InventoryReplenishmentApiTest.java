package vn.greenmart.inventory;

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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.service.InventoryReplenishmentService;
import vn.greenmart.sales.service.PosIdempotencyService;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class InventoryReplenishmentApiTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate DAY = LocalDate.now(BUSINESS_ZONE);
    private static final String ENDPOINT = "/api/v1/inventory/81/81/replenishment";

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
    @Autowired InventoryReplenishmentService replenishment;
    @Autowired TransactionTemplate transactions;

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.execute("TRUNCATE branch, category, app_user, forecast_run CASCADE");
        jdbc.update("INSERT INTO branch(id,code,name) VALUES (81,'GM-001','Kho 1'),(82,'GM-002','Kho 2')");
        jdbc.update("INSERT INTO category(id,name) VALUES(81,'Milk')");
        jdbc.update("""
                INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price)
                VALUES (81,'MILK','Milk','chai',81,29000,10000)
                """);
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,full_name,role) VALUES
                (810,'manager','hash','Manager','BRANCH_MANAGER'),(811,'other','hash','Other','BRANCH_MANAGER')
                """);
        jdbc.update("INSERT INTO user_branch(user_id,branch_id) VALUES(810,81),(811,82)");
        jdbc.update("""
                INSERT INTO inventory(branch_id,product_id,on_hand,reserved,reorder_point,reorder_pack_size)
                VALUES(81,81,32,2,18,12)
                """);
        jdbc.update("""
                INSERT INTO forecast_run(id,model_version,status,started_at,finished_at) VALUES
                (91,'old','DONE',?,?),(92,'current','DONE',?,?),
                (93,'running','RUNNING',?,NULL),(94,'incomplete','DONE',?,?)
                """, DAY.minusDays(3).atTime(2, 0).atZone(BUSINESS_ZONE).toOffsetDateTime(),
                DAY.minusDays(3).atTime(2, 10).atZone(BUSINESS_ZONE).toOffsetDateTime(),
                DAY.atTime(2, 0).atZone(BUSINESS_ZONE).toOffsetDateTime(),
                DAY.atTime(2, 10).atZone(BUSINESS_ZONE).toOffsetDateTime(),
                DAY.atTime(2, 30).atZone(BUSINESS_ZONE).toOffsetDateTime(),
                DAY.atTime(2, 40).atZone(BUSINESS_ZONE).toOffsetDateTime(),
                DAY.atTime(2, 50).atZone(BUSINESS_ZONE).toOffsetDateTime());
        jdbc.update("""
                INSERT INTO forecast_result(run_id,branch_id,product_id,horizon,target_date,predicted_qty)
                SELECT 92,81,81,7,CAST(? AS date) + offsets.n,12 FROM generate_series(0,6) AS offsets(n)
                UNION ALL
                SELECT 92,81,81,30,CAST(? AS date) + offsets.n,12 FROM generate_series(0,8) AS offsets(n)
                UNION ALL
                SELECT 93,81,81,7,CAST(? AS date) + offsets.n,999 FROM generate_series(0,6) AS offsets(n)
                """, DAY, DAY, DAY);
        jdbc.update("""
                INSERT INTO forecast_result(run_id,branch_id,product_id,horizon,target_date,predicted_qty) VALUES
                (91,81,81,7,?,12),(91,81,81,7,?,12),(94,81,81,7,?,999)
                """, DAY.minusDays(2), DAY.minusDays(1), DAY);
        jdbc.update("""
                INSERT INTO daily_sales(sale_date,branch_id,product_id,qty_sold,qty_net,revenue) VALUES
                (?,81,81,8,8,80000),(?,81,81,16,16,160000)
                """, DAY.minusDays(2), DAY.minusDays(1));
    }

    @Test
    void implementsBusinessExampleAndKeepsManualReorderPointAsFallback() throws Exception {
        replenishment.refresh(DAY);
        replenishment.refresh(DAY);
        mvc.perform(get(ENDPOINT).with(user("810").roles("BRANCH_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(30))
                .andExpect(jsonPath("$.safetyStock").value(10))
                .andExpect(jsonPath("$.reorderPoint").value(34))
                .andExpect(jsonPath("$.manualReorderPoint").value(18))
                .andExpect(jsonPath("$.forecastDemand").value(108))
                .andExpect(jsonPath("$.suggestedQuantity").value(96));
        mvc.perform(get("/api/v1/inventory/81/81").with(user("810").roles("BRANCH_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reorderPoint").value(34))
                .andExpect(jsonPath("$.stockStatus").value("LOW"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type='stock.low'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM inventory WHERE branch_id=81 AND product_id=81",
                Long.class)).isZero();

        jdbc.update("UPDATE inventory SET reorder_plan_date=? WHERE branch_id=81 AND product_id=81", DAY.minusDays(1));
        mvc.perform(get(ENDPOINT).with(user("810").roles("BRANCH_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reorderPoint").value(18))
                .andExpect(jsonPath("$.suggestedQuantity").value(nullValue()));
        mvc.perform(get("/api/v1/alerts/low-stock").with(user("810").roles("BRANCH_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());

        replenishment.refresh(DAY.plusDays(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_alert WHERE closed_at IS NULL",
                Integer.class)).isZero();
    }

    @Test
    void subtractsInTransitStockAndUsesTheFullThirtyDayForecastWhenAvailable() throws Exception {
        jdbc.update("""
                UPDATE forecast_result SET predicted_qty=18
                WHERE run_id=92 AND horizon=30 AND target_date=?
                """, DAY.plusDays(8));
        replenishment.refresh(DAY);
        jdbc.update("""
                INSERT INTO stock_document(id,code,type,branch_id,to_branch_id,status,created_by,approved_by,approved_at)
                VALUES(81,'PC-81','TRANSFER',82,81,'IN_TRANSIT',810,811,CURRENT_TIMESTAMP),
                      (82,'PC-82','TRANSFER',82,81,'DRAFT',810,NULL,NULL)
                """);
        jdbc.update("""
                INSERT INTO stock_document_item(document_id,product_id,quantity) VALUES (81,81,24),(82,81,200)
                """);
        mvc.perform(get(ENDPOINT).with(user("810").roles("BRANCH_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.forecastDemand").value(114))
                .andExpect(jsonPath("$.incoming").value(24)).andExpect(jsonPath("$.suggestedQuantity").value(72));

        jdbc.update("UPDATE stock_document_item SET quantity=150 WHERE document_id=81");
        mvc.perform(get(ENDPOINT).with(user("810").roles("BRANCH_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.suggestedQuantity").value(0));

        jdbc.update("DELETE FROM forecast_result WHERE run_id=92 AND horizon=30");
        replenishment.refresh(DAY);
        mvc.perform(get(ENDPOINT).with(user("810").roles("BRANCH_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.forecastDemand").value(108));
    }

    @Test
    void skipsRefreshWhenAnotherInstanceOwnsTheLock() {
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(hashtextextended('greenmart.inventory.replenishment', 0))");
            CompletableFuture.runAsync(() -> replenishment.refresh(DAY)).orTimeout(10, TimeUnit.SECONDS).join();
            assertThat(jdbc.queryForObject("SELECT forecast_reorder_point FROM inventory WHERE branch_id=81",
                    Integer.class)).isNull();
        });

        replenishment.refresh(DAY);
        assertThat(jdbc.queryForObject("SELECT forecast_reorder_point FROM inventory WHERE branch_id=81",
                Integer.class)).isEqualTo(34);
    }

    @Test
    void letsOnlyAssignedBranchManagerEditManualThresholdAndPackSize() throws Exception {
        String settings = """
                {"manualReorderPoint":33,"packSize":6}
                """;
        mvc.perform(put(ENDPOINT).with(user("811").roles("BRANCH_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(settings))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
        mvc.perform(put(ENDPOINT).with(user("810").roles("STOREKEEPER"))
                        .contentType(MediaType.APPLICATION_JSON).content(settings)).andExpect(status().isForbidden());
        mvc.perform(get(ENDPOINT).with(user("810").roles("CASHIER"))).andExpect(status().isForbidden());
        mvc.perform(put(ENDPOINT).with(user("810").roles("BRANCH_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(settings))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reorderPoint").value(33))
                .andExpect(jsonPath("$.packSize").value(6)).andExpect(jsonPath("$.suggestedQuantity").value(nullValue()));
    }
}
