package vn.greenmart.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.service.InventoryService;
import vn.greenmart.sales.service.PosIdempotencyService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class StockAlertApiTest {
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
    @Autowired InventoryService inventory;
    @Autowired PlatformTransactionManager transactionManager;

    private static final String ALERTS = "/api/v1/alerts/low-stock";

    @BeforeEach void fixtures() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.execute("TRUNCATE branch,category,app_user CASCADE");
        jdbc.update("INSERT INTO branch(id,code,name) VALUES(81,'B81','Branch 81'),(82,'B82','Branch 82')");
        jdbc.update("INSERT INTO category(id,name) VALUES(81,'Milk')");
        jdbc.update("""
                INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price) VALUES
                (81,'MILK','Milk','hop',81,29000,10000),(82,'TEA','Tea','hop',81,15000,5000)
                """);
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,full_name,role) VALUES
                (811,'manager','hash','Manager','BRANCH_MANAGER'),
                (812,'owner','hash','Owner','OWNER'),(813,'unassigned','hash','Unassigned','BRANCH_MANAGER')
                """);
        jdbc.update("INSERT INTO user_branch(user_id,branch_id) VALUES(811,81)");
        jdbc.update("""
                INSERT INTO inventory(branch_id,product_id,on_hand,reserved,reorder_point)
                VALUES(81,81,5,0,3),(81,82,5,0,3),(82,81,5,0,3)
                """);
    }

    @Test void returnsOpenAlertsOutFirstAndScopesThemToAssignedBranches() throws Exception {
        var tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            inventory.reserve(81L, 81L, 5, 901L);
            inventory.reserve(81L, 82L, 2, 902L);
            inventory.reserve(82L, 81L, 5, 903L);
        });
        mvc.perform(get(ALERTS).param("branchId", "81").with(user("811").roles("BRANCH_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].severity").value("OUT"))
                .andExpect(jsonPath("$[0].available").value(0))
                .andExpect(jsonPath("$[1].severity").value("LOW"))
                .andExpect(jsonPath("$[1].sku").value("TEA"))
                .andExpect(jsonPath("$[1].reorderPoint").value(3));
        mvc.perform(get(ALERTS).with(user("811").roles("BRANCH_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get(ALERTS).with(user("812").roles("OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get(ALERTS).with(user("813").roles("BRANCH_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        tx.executeWithoutResult(status -> inventory.release(81L, 82L, 2, 902L));
        mvc.perform(get(ALERTS).with(user("811").roles("BRANCH_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test void rejectsForbiddenBranchRoleAndInvalidId() throws Exception {
        mvc.perform(get(ALERTS).param("branchId", "82").with(user("811").roles("BRANCH_MANAGER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
        mvc.perform(get(ALERTS).with(user("811").roles("CASHIER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get(ALERTS).param("branchId", "0").with(user("811").roles("BRANCH_MANAGER")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get(ALERTS)).andExpect(status().isUnauthorized());
    }

    @Test void emitsOnlyOnOpeningAndDoesNotPersistOnRollback() {
        var tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            inventory.reserve(81L, 81L, 2, 901L);
            assertThat(eventSeverities()).containsExactly("LOW");
            status.setRollbackOnly();
        });
        assertThat(eventSeverities()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_alert", Integer.class)).isZero();

        tx.executeWithoutResult(status -> inventory.reserve(81L, 81L, 2, 901L));
        assertThat(jdbc.queryForMap("""
                SELECT aggregate_type, aggregate_id, payload->>'branchId' AS branch, payload->>'productId' AS product,
                       payload->>'alertId' AS alert, payload->>'severity' AS severity
                FROM outbox_event WHERE event_type='stock.low'
                """)).containsEntry("branch", "81").containsEntry("product", "81")
                .containsEntry("severity", "LOW").containsEntry("aggregate_type", "stock_alert")
                .containsEntry("aggregate_id", openAlertId()).containsEntry("alert", openAlertId());
        tx.executeWithoutResult(status -> inventory.reserve(81L, 81L, 3, 902L));
        assertThat(jdbc.queryForObject("SELECT severity FROM stock_alert WHERE closed_at IS NULL", String.class))
                .isEqualTo("OUT");
        tx.executeWithoutResult(status -> inventory.release(81L, 81L, 5, 903L));
        assertThat(eventSeverities()).containsExactly("LOW");
        tx.executeWithoutResult(status -> inventory.reserve(81L, 81L, 5, 904L));
        assertThat(eventSeverities()).containsExactly("LOW", "OUT");
    }

    private String openAlertId() {
        return jdbc.queryForObject("SELECT id::text FROM stock_alert WHERE closed_at IS NULL", String.class);
    }

    private List<String> eventSeverities() {
        return jdbc.queryForList("""
                SELECT payload->>'severity' FROM outbox_event
                WHERE event_type='stock.low' ORDER BY id
                """, String.class);
    }
}
