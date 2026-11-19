package vn.greenmart.forecast;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.identity.security.JwtTokens;
import vn.greenmart.inventory.PostgresTestImage;
import vn.greenmart.sales.service.PosIdempotencyService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "management.endpoint.health.group.readiness.include=readinessState,db"
})
@AutoConfigureMockMvc
@Testcontainers
class ForecastQueryApiTest {
    private static final String ENDPOINT = "/api/v1/forecasts";
    private static final LocalDate CUTOFF = LocalDate.of(2026, 11, 18);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.forecast_user", () -> "forecast_ro");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokens tokens;
    @Autowired ObjectMapper json;
    @Autowired TransactionTemplate transactions;
    @MockitoBean PosIdempotencyService posIdempotencyService;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE branch, category, app_user, forecast_run CASCADE");
        jdbc.update("INSERT INTO branch(id,code,name) VALUES(11,'B11','Branch 11'),(12,'B12','Branch 12')");
        jdbc.update("INSERT INTO category(id,name) VALUES(21,'Milk')");
        jdbc.update("""
                INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price) VALUES
                (21,'MILK','Milk','chai',21,20000,10000),(22,'OTHER','Other','hop',21,10000,5000)
                """);
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,full_name,role) VALUES
                (101,'owner','hash','Owner','OWNER'),(102,'manager','hash','Manager','BRANCH_MANAGER'),
                (103,'keeper','hash','Keeper','STOREKEEPER'),(104,'cashier','hash','Cashier','CASHIER'),
                (105,'admin','hash','Admin','ADMIN'),(106,'unassigned','hash','Unassigned','BRANCH_MANAGER')
                """);
        jdbc.update("INSERT INTO user_branch(user_id,branch_id) VALUES(102,11),(103,11),(104,11)");
        jdbc.update("""
                INSERT INTO forecast_run(id,status,model_version,started_at,finished_at) VALUES
                (10,'DONE','old','2026-11-01T00:00:00+07:00','2026-11-01T00:10:00+07:00'),
                (20,'DONE','current','2026-11-19T00:00:00+07:00','2026-11-19T00:10:00+07:00'),
                (30,'RUNNING','running','2026-11-19T01:00:00+07:00',NULL),
                (40,'FAILED','failed','2026-11-19T02:00:00+07:00','2026-11-19T02:10:00+07:00')
                """);
        predictions(10, 11, 21, 7, CUTOFF.minusDays(18), "9");
        predictions(10, 11, 21, 30, CUTOFF.minusDays(18), "10");
        predictions(20, 11, 21, 7, CUTOFF, "1.25");
        predictions(20, 11, 21, 30, CUTOFF, "2.4");
        predictions(20, 12, 21, 7, CUTOFF, "99");
        predictions(20, 11, 22, 7, CUTOFF, "88");
        predictions(30, 11, 21, 7, CUTOFF, "777");
        predictions(40, 11, 21, 7, CUTOFF, "666");
        jdbc.update("""
                INSERT INTO daily_sales(sale_date,branch_id,product_id,qty_sold,qty_returned,qty_net,revenue) VALUES
                ('2026-11-19',11,21,3,0,3,60000),('2026-11-20',11,21,0,0,0,0),
                ('2026-11-21',11,21,0,2,-2,0),('2026-11-19',12,21,99,0,99,0),
                ('2026-11-19',11,22,88,0,88,0),('2026-12-31',11,21,5,0,5,0)
                """);
    }

    private void predictions(long run, long branch, long product, int horizon, LocalDate cutoff, String quantity) {
        jdbc.update("""
                INSERT INTO forecast_result(run_id,branch_id,product_id,horizon,target_date,predicted_qty)
                SELECT ?,?,?,?,CAST(? AS date)+n,? FROM generate_series(1,?) AS days(n)
                """, run, branch, product, horizon, cutoff, new BigDecimal(quantity), horizon);
    }

    private MockHttpServletRequestBuilder request(String query, long userId, String role) {
        return get(ENDPOINT + query).header("Authorization", "Bearer " + tokens.issue(userId, role));
    }

    private MockHttpServletRequestBuilder owner(String query) {
        return request(query, 101, "OWNER");
    }

    @Test
    void latestDoneReturnsBothHorizonsWithActualsAndStableCutoff() throws Exception {
        var result = mvc.perform(owner("?branchId=11&productId=21"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.runId").value(20))
                .andExpect(jsonPath("$.cutoffDate").value("2026-11-18"))
                .andExpect(jsonPath("$.modelVersion").value("current"))
                .andExpect(jsonPath("$.branch.id").value(11)).andExpect(jsonPath("$.branch.code").value("B11"))
                .andExpect(jsonPath("$.branch.name").value("Branch 11"))
                .andExpect(jsonPath("$.productId").value(21)).andExpect(jsonPath("$.sku").value("MILK"))
                .andExpect(jsonPath("$.productName").value("Milk")).andExpect(jsonPath("$.unit").value("chai"))
                .andExpect(jsonPath("$.horizons.length()").value(2))
                .andExpect(jsonPath("$.horizons[0].horizon").value(7))
                .andExpect(jsonPath("$.horizons[0].totalPredictedQty").value(8.75))
                .andExpect(jsonPath("$.horizons[0].points.length()").value(7))
                .andExpect(jsonPath("$.horizons[0].points[0].predictedQty").value(1.25))
                .andExpect(jsonPath("$.horizons[0].points[0].actualQty").value(3))
                .andExpect(jsonPath("$.horizons[0].points[1].actualQty").value(0))
                .andExpect(jsonPath("$.horizons[0].points[2].actualQty").value(-2))
                .andExpect(jsonPath("$.horizons[0].points[3].actualQty").value(nullValue()))
                .andExpect(jsonPath("$.horizons[1].horizon").value(30))
                .andExpect(jsonPath("$.horizons[1].totalPredictedQty").value(72.0))
                .andExpect(jsonPath("$.horizons[1].points.length()").value(30)).andReturn();
        var body = json.readTree(result.getResponse().getContentAsString());
        assertThat(OffsetDateTime.parse(body.path("generatedAt").asText()).toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-11-19T00:10:00+07:00").toInstant());
        for (var horizon : body.path("horizons")) {
            for (int index = 0; index < horizon.path("horizon").asInt(); index++) {
                assertThat(horizon.path("points").get(index).path("date").asText())
                        .isEqualTo(CUTOFF.plusDays(index + 1).toString());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 30})
    void filtersHorizonWithoutChangingCutoff(int horizon) throws Exception {
        mvc.perform(owner("?branchId=11&productId=21&horizon=" + horizon))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cutoffDate").value(CUTOFF.toString()))
                .andExpect(jsonPath("$.horizons.length()").value(1))
                .andExpect(jsonPath("$.horizons[0].horizon").value(horizon))
                .andExpect(jsonPath("$.horizons[0].points.length()").value(horizon));
    }

    @Test
    void explicitRunReturnsItsHistoricalDates() throws Exception {
        mvc.perform(owner("?branchId=11&productId=21&runId=10&horizon=7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.runId").value(10))
                .andExpect(jsonPath("$.cutoffDate").value("2026-10-31"))
                .andExpect(jsonPath("$.horizons[0].totalPredictedQty").value(63))
                .andExpect(jsonPath("$.horizons[0].points[0].actualQty").value(nullValue()));
    }

    @ParameterizedTest
    @ValueSource(longs = {30, 40, 999})
    void rejectsNonDoneOrUnknownRuns(long runId) throws Exception {
        mvc.perform(owner("?branchId=11&productId=21&runId=" + runId))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void missingPairInLatestRunDoesNotFallBackToOlderRun() throws Exception {
        jdbc.update("DELETE FROM forecast_result WHERE run_id=20 AND branch_id=11 AND product_id=21");
        mvc.perform(owner("?branchId=11&productId=21"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void noDoneRunReturnsNotFound() throws Exception {
        jdbc.update("UPDATE forecast_run SET status='FAILED' WHERE status='DONE'");
        mvc.perform(owner("?branchId=11&productId=21"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void doneRunUsesFinishedTimeAndIdToChooseLatest() throws Exception {
        jdbc.update("UPDATE forecast_run SET finished_at=(SELECT finished_at FROM forecast_run WHERE id=20) WHERE id=10");
        mvc.perform(owner("?branchId=11&productId=21"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.runId").value(20));
        jdbc.update("UPDATE forecast_run SET finished_at='2026-11-20T00:00:00+07:00' WHERE id=10");
        mvc.perform(owner("?branchId=11&productId=21"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.runId").value(10));
    }

    @Test
    void managerAndKeeperAreRestrictedToAssignedBranchAndDenialIsAudited() throws Exception {
        mvc.perform(request("?branchId=11&productId=21", 102, "BRANCH_MANAGER")).andExpect(status().isOk());
        mvc.perform(request("?branchId=11&productId=21", 103, "STOREKEEPER")).andExpect(status().isOk());
        mvc.perform(request("?branchId=12&productId=21&runId=20", 102, "BRANCH_MANAGER"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
        mvc.perform(request("?branchId=12&productId=21", 103, "STOREKEEPER"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
        mvc.perform(request("?branchId=11&productId=21", 106, "BRANCH_MANAGER"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='BRANCH_FORBIDDEN'", Integer.class))
                .isEqualTo(3);
        mvc.perform(owner("?branchId=12&productId=21"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.horizons[0].totalPredictedQty").value(693));
    }

    @Test
    void roleAndLoginAreRequired() throws Exception {
        mvc.perform(request("?branchId=11&productId=21", 104, "CASHIER"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(request("?branchId=11&productId=21", 105, "ADMIN"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get(ENDPOINT + "?branchId=11&productId=21"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?branchId=11", "?productId=21", "?branchId=0&productId=21",
            "?branchId=11&productId=-1", "?branchId=11&productId=21&runId=0",
            "?branchId=11&productId=21&horizon=14", "?branchId=11&productId=21&horizon=0",
            "?branchId=abc&productId=21", "?branchId=11&productId=21&horizon=abc",
            "?branchId=11&productId=21&runId=9223372036854775808"})
    void invalidParametersReturnContractError(String query) throws Exception {
        mvc.perform(owner(query)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void unknownProductOrBranchAndMissingRequestedHorizonReturnNotFound() throws Exception {
        mvc.perform(owner("?branchId=11&productId=999")).andExpect(status().isNotFound());
        mvc.perform(owner("?branchId=999&productId=21")).andExpect(status().isNotFound());
        mvc.perform(owner("?branchId=12&productId=21&horizon=30"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void historicalDataRemainsVisibleForInactiveProduct() throws Exception {
        jdbc.update("UPDATE product SET is_active=false WHERE id=21");
        mvc.perform(owner("?branchId=11&productId=21&runId=10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.runId").value(10));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isZero();
    }

    @Test
    void existingTokenCannotKeepRevokedBranchAccessOrAccessForLockedUser() throws Exception {
        String token = tokens.issue(102, "BRANCH_MANAGER");
        mvc.perform(get(ENDPOINT + "?branchId=11&productId=21").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        jdbc.update("DELETE FROM user_branch WHERE user_id=102");
        mvc.perform(get(ENDPOINT + "?branchId=11&productId=21").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
        jdbc.update("INSERT INTO user_branch(user_id,branch_id) VALUES(102,11)");
        jdbc.update("UPDATE app_user SET is_active=false WHERE id=102");
        mvc.perform(get(ENDPOINT + "?branchId=11&productId=21").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void tokenRoleCannotOverrideCurrentUserRole() throws Exception {
        mvc.perform(request("?branchId=11&productId=21", 104, "OWNER"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void newActualsAreVisibleWithoutChangingSavedPredictionsOrCutoff() throws Exception {
        jdbc.update("""
                INSERT INTO daily_sales(sale_date,branch_id,product_id,qty_sold,qty_returned,qty_net,revenue)
                VALUES('2026-11-22',11,21,6,1,5,100000)
                """);
        jdbc.update("UPDATE daily_sales SET qty_returned=4,qty_net=-1 WHERE sale_date='2026-11-19' AND branch_id=11 AND product_id=21");
        mvc.perform(owner("?branchId=11&productId=21"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.runId").value(20))
                .andExpect(jsonPath("$.cutoffDate").value(CUTOFF.toString()))
                .andExpect(jsonPath("$.horizons[0].totalPredictedQty").value(8.75))
                .andExpect(jsonPath("$.horizons[0].points[0].actualQty").value(-1))
                .andExpect(jsonPath("$.horizons[0].points[3].actualQty").value(5))
                .andExpect(jsonPath("$.horizons[1].points[3].actualQty").value(5));
    }

    @Test
    void zeroAndSmallFractionalPredictionsKeepTheirPrecision() throws Exception {
        jdbc.update("""
                UPDATE forecast_result SET predicted_qty=CASE WHEN target_date='2026-11-19' THEN 0 ELSE 0.0001 END
                WHERE run_id=20 AND branch_id=11 AND product_id=21 AND horizon=7
                """);
        mvc.perform(owner("?branchId=11&productId=21&horizon=7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.horizons[0].totalPredictedQty").value(0.0006))
                .andExpect(jsonPath("$.horizons[0].points[0].predictedQty").value(0))
                .andExpect(jsonPath("$.horizons[0].points[1].predictedQty").value(0.0001));
    }

    @Test
    void newRunBecomesVisibleOnlyAfterResultsAndDoneStatusCommitTogether() throws Exception {
        var ready = new CountDownLatch(1);
        var releaseCommit = new CountDownLatch(1);
        var publisher = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(transaction -> {
            jdbc.update("""
                    INSERT INTO forecast_run(id,status,model_version,finished_at)
                    VALUES(50,'DONE','published','2026-11-20T00:00:00+07:00')
                    """);
            predictions(50, 11, 21, 7, CUTOFF.plusDays(1), "3");
            predictions(50, 11, 21, 30, CUTOFF.plusDays(1), "4");
            ready.countDown();
            try {
                if (!releaseCommit.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Commit was not released");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }));
        try {
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            mvc.perform(owner("?branchId=11&productId=21"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.runId").value(20))
                    .andExpect(jsonPath("$.horizons[0].totalPredictedQty").value(8.75));
        } finally {
            releaseCommit.countDown();
            publisher.get(20, TimeUnit.SECONDS);
        }
        mvc.perform(owner("?branchId=11&productId=21"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.runId").value(50))
                .andExpect(jsonPath("$.modelVersion").value("published"))
                .andExpect(jsonPath("$.cutoffDate").value("2026-11-19"))
                .andExpect(jsonPath("$.horizons[0].totalPredictedQty").value(21))
                .andExpect(jsonPath("$.horizons[1].totalPredictedQty").value(120));
    }
}
