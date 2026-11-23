package vn.greenmart.promotion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.PostgresTestImage;
import vn.greenmart.common.audit.AuditLogRepository;
import vn.greenmart.promotion.service.PromotionExpiryService;
import vn.greenmart.sales.service.PromotionService;
import vn.greenmart.sales.service.PosIdempotencyService;
import vn.greenmart.sales.dto.IdempotencyResult;
import vn.greenmart.sales.dto.OrderResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "greenmart.promotion.expiry.cron=-")
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(username = "42601", roles = "OWNER")
class PromotionExpiryIntegrationTest {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private Clock clock;
    @MockitoBean private PosIdempotencyService idempotency;
    @MockitoSpyBean private AuditLogRepository audit;
    @Autowired private PromotionExpiryService expiry;
    @Autowired private PromotionService usage;
    @Autowired private PlatformTransactionManager transactions;

    @BeforeEach
    void fixture() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(idempotency.execute(anyString(), any(), any())).thenAnswer(inv -> {
            Supplier<OrderResponse> action = inv.getArgument(2);
            return new IdempotencyResult(true, action.get());
        });
        jdbc.execute("""
                TRUNCATE promotion, audit_log, app_user, branch, product, category RESTART IDENTITY CASCADE;
                INSERT INTO app_user(id,username,password_hash,full_name,role,is_active)
                VALUES(42601,'owner_426','test-only-unused-hash','Owner','OWNER',true),
                      (42602,'cashier_426','test-only-unused-hash','Cashier','CASHIER',true);
                INSERT INTO branch(id,code,name,is_active) VALUES(42601,'GM-426','Branch',true);
                INSERT INTO user_branch(user_id,branch_id) VALUES(42602,42601);
                INSERT INTO category(id,name) VALUES(42601,'Category');
                INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price,is_active)
                VALUES(42601,'QA-426','Product','box',42601,1000,500,true);
                INSERT INTO inventory(branch_id,product_id,on_hand,reserved,version) VALUES(42601,42601,10,0,0);
                """);
    }

    @Test
    void historicalCreateIsRejected() throws Exception {
        mvc.perform(post("/api/v1/promotions").contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"OLD-426","type":"AMOUNT","level":"ORDER","value":100,
                 "startAt":"1970-01-01T00:00:00Z","endAt":"1970-01-02T00:00:00Z"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM promotion", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class)).isZero();
    }

    @Test
    void expiredCannotActivate() throws Exception {
        jdbc.update("""
                INSERT INTO promotion(id,code,type,level,value,start_at,end_at,status,used_count)
                VALUES(42601,'OLD-426','AMOUNT','ORDER',100,'1970-01-01','1970-01-02','DRAFT',0)
                """);
        mvc.perform(post("/api/v1/promotions/42601/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        assertThat(statusOf(42601)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class)).isZero();
    }

    @Test
    void futureCampaignCanActivateButDoesNotApplyBeforeStart() throws Exception {
        mvc.perform(post("/api/v1/promotions").contentType(MediaType.APPLICATION_JSON).content(request("2030-01-02T00:00:00Z", "2030-01-03T00:00:00Z")))
                .andExpect(status().isCreated());
        Long id = jdbc.queryForObject("SELECT id FROM promotion", Long.class);
        mvc.perform(post("/api/v1/promotions/{id}/status", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        quote(1000);
        assertThat(expiry.endExpiredPromotions()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"2029-12-31T23:59:59.999Z,1000", "2030-01-01T00:00:00Z,800",
            "2030-01-01T00:59:59.999Z,800", "2030-01-01T01:00:00Z,1000"})
    void itemAndOrderQuoteUseHalfOpenTimeRangeEvenBeforeJob(String at, int total) throws Exception {
        insert(42601, "ITEM", "ACTIVE", NOW, NOW.plusSeconds(3600));
        insert(42602, "ORDER", "ACTIVE", NOW, NOW.plusSeconds(3600));
        when(clock.instant()).thenReturn(Instant.parse(at));
        quote(total);
        assertThat(statusOf(42601)).isEqualTo("ACTIVE");
    }

    @Test
    void activeAtFilterExcludesExactEnd() throws Exception {
        insert(42601, "ORDER", "ACTIVE", NOW, NOW.plusSeconds(3600));
        mvc.perform(get("/api/v1/promotions").param("activeAt", NOW.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/promotions").param("activeAt", NOW.plusSeconds(3600).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void expiredOrEqualEndCannotBeSavedOrActivated() throws Exception {
        insert(42601, "ORDER", "PAUSED", NOW.minusSeconds(60), NOW);
        mvc.perform(put("/api/v1/promotions/42601").contentType(MediaType.APPLICATION_JSON)
                        .content(request("2029-12-31T23:00:00Z", NOW.toString())))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/promotions/42601/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict());
        assertThat(statusOf(42601)).isEqualTo("PAUSED");
    }

    @Test
    void expiryAfterQuoteReturnsTotalChangedWithoutSaleOrUsage() throws Exception {
        insert(42601, "ITEM", "ACTIVE", NOW, NOW.plusSeconds(60));
        insert(42602, "ORDER", "ACTIVE", NOW, NOW.plusSeconds(60));
        quote(800);
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        mvc.perform(post("/api/v1/orders/pos").with(user("42602").roles("CASHIER"))
                        .header("Idempotency-Key", "expiry-426").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"branchId":42601,"items":[{"productId":42601,"quantity":1}],
                             "payment":{"method":"CASH","cashReceived":1000},"expectedTotal":800}
                            """))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TOTAL_CHANGED"));
        assertThat(jdbc.queryForObject("SELECT sum(used_count) FROM promotion", Long.class)).isZero();
        for (String table : new String[]{"sales_order", "payment", "inventory_transaction", "outbox_event"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory", Integer.class)).isEqualTo(10);
    }

    @Test
    void jobEndsAllExpiredStatesOnlyOnceAndPreservesUsage() {
        for (int i = 0; i < 3; i++) insert(42601 + i, "ORDER", new String[]{"DRAFT", "ACTIVE", "PAUSED"}[i], NOW.minusSeconds(60), NOW);
        insert(42604, "ORDER", "ACTIVE", NOW.plusSeconds(60), NOW.plusSeconds(120));
        jdbc.update("UPDATE promotion SET used_count=7 WHERE id=42602");
        assertThat(expiry.endExpiredPromotions()).isEqualTo(3);
        assertThat(expiry.endExpiredPromotions()).isZero();
        assertThat(jdbc.queryForObject("SELECT used_count FROM promotion WHERE id=42602", Integer.class)).isEqualTo(7);
        assertThat(statusOf(42604)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE user_id IS NULL AND action='CHANGE_PROMOTION_STATUS'", Integer.class)).isEqualTo(3);
    }

    @Test
    void auditFailureRollsBackExpiry() {
        insert(42601, "ORDER", "ACTIVE", NOW.minusSeconds(60), NOW);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).saveAll(any());
        assertThatThrownBy(expiry::endExpiredPromotions).isInstanceOf(IllegalStateException.class);
        assertThat(statusOf(42601)).isEqualTo("ACTIVE");
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE,0,60,true", "ACTIVE,1,60,false", "ACTIVE,-60,0,false",
            "DRAFT,-60,60,false", "PAUSED,-60,60,false", "ENDED,-60,60,false"})
    void usageRequiresActiveAndCurrentPeriod(String state, int start, int end, boolean accepted) {
        insert(42601, "ORDER", state, NOW.plusSeconds(start), NOW.plusSeconds(end));
        Boolean result = new TransactionTemplate(transactions).execute(s -> usage.recordUsage(42601L));
        assertThat(result).isEqualTo(accepted);
        assertThat(jdbc.queryForObject("SELECT used_count FROM promotion", Integer.class)).isEqualTo(accepted ? 1 : 0);
    }

    @Test
    void jobSkipsLockedRowAndPicksItUpOnNextTick() throws Exception {
        insert(42601, "ORDER", "ACTIVE", NOW.minusSeconds(60), NOW);
        insert(42602, "ORDER", "ACTIVE", NOW.minusSeconds(60), NOW);
        try (var worker = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactions).execute(s -> {
                jdbc.queryForList("SELECT id FROM promotion WHERE id=42601 FOR NO KEY UPDATE");
                try { assertThat(worker.submit(expiry::endExpiredPromotions).get(5, TimeUnit.SECONDS)).isEqualTo(1); }
                catch (Exception e) { throw new IllegalStateException(e); }
                assertThat(statusOf(42601)).isEqualTo("ACTIVE");
                return null;
            });
        }
        assertThat(expiry.endExpiredPromotions()).isEqualTo(1);
        assertThat(expiry.endExpiredPromotions()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class)).isEqualTo(2);
    }

    @Test
    void usageWaitingAcrossEndChecksTimeAfterRowLock() throws Exception {
        insert(42601, "ORDER", "ACTIVE", NOW, NOW.plusSeconds(60));
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var holder = workers.submit(() -> new TransactionTemplate(transactions).execute(s -> {
                jdbc.queryForList("SELECT id FROM promotion WHERE id=42601 FOR NO KEY UPDATE");
                locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("lock timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return null;
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var result = workers.submit(() -> new TransactionTemplate(transactions).execute(s -> usage.recordUsage(42601L)));
                await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%promotion%'", Integer.class) > 0);
                when(clock.instant()).thenReturn(NOW.plusSeconds(60));
                release.countDown();
                assertThat(result.get(5, TimeUnit.SECONDS)).isFalse();
            } finally { release.countDown(); }
            holder.get(5, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT used_count FROM promotion", Integer.class)).isZero();
    }

    private void insert(long id, String level, String status, Instant start, Instant end) {
        jdbc.update("INSERT INTO promotion(id,code,type,level,value,start_at,end_at,status,used_count) VALUES(?,?,'AMOUNT',?,100,?,?,?,0)",
                id, "P-" + id, level, Timestamp.from(start), Timestamp.from(end), status);
        if (level.equals("ITEM")) jdbc.update("INSERT INTO promotion_product(promotion_id,product_id) VALUES(?,42601)", id);
    }
    private String statusOf(long id) { return jdbc.queryForObject("SELECT status FROM promotion WHERE id=?", String.class, id); }
    private String request(String start, String end) {
        return "{\"code\":\"P-426\",\"type\":\"AMOUNT\",\"level\":\"ORDER\",\"value\":100,\"startAt\":\"" + start + "\",\"endAt\":\"" + end + "\"}";
    }
    private void quote(int expected) throws Exception {
        mvc.perform(post("/api/v1/orders/quote").with(user("42602").roles("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"branchId\":42601,\"items\":[{\"productId\":42601,\"quantity\":1}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(expected));
    }
}
