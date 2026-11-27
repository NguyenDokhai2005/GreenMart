package vn.greenmart.customer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.PostgresTestImage;
import vn.greenmart.integration.channel.ChannelSignatures;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "management.endpoint.health.group.readiness.include=readinessState,db",
        "greenmart.outbox.enabled=false", "greenmart.order.expiry.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InactivePosCustomerTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.6-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("spring.flyway.placeholders.forecast_user", () -> "forecast_ro");
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired ChannelSignatures signatures;

    @BeforeEach void fixtures() {
        jdbc.execute("TRUNCATE branch,category,customer,app_user,promotion CASCADE");
        jdbc.execute("TRUNCATE audit_log,outbox_event");
        jdbc.update("INSERT INTO sales_channel(code,name) VALUES('POS','POS') ON CONFLICT (code) DO NOTHING");
        jdbc.update("INSERT INTO branch(id,code,name) VALUES(418,'B418','Branch')");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,full_name,role) VALUES(418,'cashier418','hash','Cashier','CASHIER')");
        jdbc.update("INSERT INTO user_branch(user_id,branch_id) VALUES(418,418)");
        jdbc.update("INSERT INTO customer(id,phone,name,is_active) VALUES(418,'0900000418','Inactive',false)");
        jdbc.update("INSERT INTO category(id,name) VALUES(418,'Test')");
        jdbc.update("INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price) VALUES(418,'SKU418','Test','hop',418,10000,5000)");
        jdbc.update("INSERT INTO inventory(branch_id,product_id,on_hand,reserved) VALUES(418,418,10,0)");
    }
    private String quote(Long customer) {
        return "{\"branchId\":418,\"customerId\":" + customer + ",\"items\":[{\"productId\":418,\"quantity\":1}]}";
    }
    private String sale(Long customer) {
        String quote = quote(customer);
        return quote.substring(0, quote.length()-1) + ",\"expectedTotal\":10000,\"payment\":{\"method\":\"CASH\",\"cashReceived\":10000}}";
    }
    private ResultActions complete(Long customer, String key) throws Exception {
        return mvc.perform(post("/api/v1/orders/pos").with(user("418").roles("CASHIER"))
                .header("Idempotency-Key", key).contentType("application/json").content(sale(customer)));
    }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    private void noSale() {
        for (String table : List.of("sales_order", "payment", "order_item", "inventory_transaction", "outbox_event")) {
            assertThat(count(table)).as(table).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory WHERE branch_id=418 AND product_id=418", Integer.class)).isEqualTo(10);
    }
    private ResultActions reactivate(String role) throws Exception {
        return mvc.perform(put("/api/v1/customers/418").with(user("418").roles(role)).contentType("application/json")
                .content("{\"phone\":\"0900000418\",\"name\":\"Inactive\",\"active\":true}"));
    }

    @ParameterizedTest @ValueSource(strings = {"CASHIER", "BRANCH_MANAGER", "OWNER", "ADMIN"})
    void exactLookupDistinguishesInactiveFromMissingAndGeneralSearchStaysHidden(String role) throws Exception {
        for (String active : List.of("default", "true")) {
            var request = get("/api/v1/customers").param("phone", " 0900000418 ").with(user("418").roles(role));
            if (!active.equals("default")) request.param("active", active);
            mvc.perform(request).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("CUSTOMER_INACTIVE"))
                    .andExpect(jsonPath("$.message").value(containsString("Liên hệ")))
                    .andExpect(jsonPath("$.content").doesNotExist());
        }
        mvc.perform(get("/api/v1/customers").param("phone", "0900000999").with(user("418").roles(role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(get("/api/v1/customers").param("q", "0900000418").with(user("418").roles(role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
    }
    @Test void quoteAndCompleteRejectBeforeAnySaleAndRetryWorksAfterReactivation() throws Exception {
        mvc.perform(post("/api/v1/orders/quote").with(user("418").roles("CASHIER"))
                        .contentType("application/json").content(quote(418L)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CUSTOMER_INACTIVE"));
        String key = UUID.randomUUID().toString();
        complete(418L, key).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CUSTOMER_INACTIVE"));
        noSale();
        reactivate("OWNER").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='REACTIVATE_CUSTOMER' AND user_id=418 AND entity_id='418'", Long.class)).isEqualTo(1);
        complete(418L, key).andExpect(status().isCreated());
        jdbc.update("UPDATE customer SET is_active=false WHERE id=418");
        complete(418L, key).andExpect(status().isOk());
        assertThat(count("sales_order")).isEqualTo(1); assertThat(count("payment")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings = {"CASHIER", "BRANCH_MANAGER"})
    void customerDeactivatedAfterQuoteIsRejectedAtCheckout(String role) throws Exception {
        jdbc.update("UPDATE customer SET is_active=true WHERE id=418");
        mvc.perform(post("/api/v1/orders/quote").with(user("418").roles(role))
                .contentType("application/json").content(quote(418L))).andExpect(status().isOk());
        jdbc.update("UPDATE customer SET is_active=false WHERE id=418");
        mvc.perform(post("/api/v1/orders/pos").with(user("418").roles(role)).header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json").content(sale(418L)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CUSTOMER_INACTIVE"));
        noSale();
    }
    private void recordPriorPurchase() {
        jdbc.update("""
                INSERT INTO sales_order(code, channel_id, branch_id, customer_id, status, subtotal, discount, total)
                SELECT 'POS-418-PRIOR', id, 418, 418, 'COMPLETED', 10000, 0, 10000
                FROM sales_channel WHERE code='POS'
                """);
    }
    @Test void reactivationAndInactiveDirectoryKeepExistingPermissions() throws Exception {
        recordPriorPurchase();
        reactivate("CASHIER").andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/customers").param("active", "false").with(user("418").roles("CASHIER")))
                .andExpect(status().isForbidden());
        for (String role : List.of("OWNER", "BRANCH_MANAGER")) {
            mvc.perform(get("/api/v1/customers").param("phone", "0900000418").param("active", "false")
                            .with(user("418").roles(role))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].active").value(false));
            reactivate(role).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
            jdbc.update("UPDATE customer SET is_active=false WHERE id=418");
        }
    }
    @Test void duplicateInactivePhoneHasGuidanceAndDoesNotCreateOrReactivateCustomer() throws Exception {
        mvc.perform(post("/api/v1/customers").with(user("418").roles("CASHIER")).contentType("application/json")
                        .content("{\"phone\":\"0900000418\",\"name\":\"New\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_PHONE"))
                .andExpect(jsonPath("$.message").value(containsString("kích hoạt lại")));
        assertThat(count("customer")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT is_active FROM customer WHERE id=418", Boolean.class)).isFalse();
    }
    @Test void failedAuditRollsBackReactivation() throws Exception {
        jdbc.execute("CREATE FUNCTION issue418_audit_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test-only audit failure'; END $$");
        jdbc.execute("CREATE TRIGGER issue418_audit_fault BEFORE INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION issue418_audit_fault()");
        try {
            reactivate("OWNER").andExpect(status().isInternalServerError());
            assertThat(jdbc.queryForObject("SELECT is_active FROM customer WHERE id=418", Boolean.class)).isFalse();
            assertThat(count("audit_log")).isZero();
        } finally { jdbc.execute("DROP FUNCTION issue418_audit_fault() CASCADE"); }
    }
    @Test void missingCustomerIsRejectedAndGuestOrActiveCustomerCanStillPay() throws Exception {
        complete(999L, UUID.randomUUID().toString()).andExpect(status().isNotFound()); noSale();
        complete(null, UUID.randomUUID().toString()).andExpect(status().isCreated());
        jdbc.update("UPDATE customer SET is_active=true WHERE id=418");
        complete(418L, UUID.randomUUID().toString()).andExpect(status().isCreated());
        assertThat(count("sales_order")).isEqualTo(2);
    }
    @Test void onlineIntakeKeepsInactiveCustomerAndHistoricalOrderLink() throws Exception {
        jdbc.update("INSERT INTO sales_channel(code,name,webhook_secret) VALUES('WEB','Web','test-only-418')");
        jdbc.update("INSERT INTO channel_branch(channel_id,branch_id) SELECT id,418 FROM sales_channel WHERE code='WEB'");
        jdbc.update("INSERT INTO channel_product(channel_id,product_id,external_sku) SELECT id,418,'WEB418' FROM sales_channel WHERE code='WEB'");
        String body = """
                {"externalOrderId":"ONLINE418","orderedAt":"%s","customer":{"name":"Channel customer","phone":"0900000418"},
                 "items":[{"externalSku":"WEB418","quantity":1,"unitPrice":10000}],"paymentMethod":"ONLINE"}
                """.formatted(java.time.Instant.now());
        mvc.perform(post("/api/v1/integrations/WEB/orders").contentType("application/json").content(body)
                        .header("X-Signature", signatures.sign("WEB", body.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("RESERVED"));
        assertThat(jdbc.queryForObject("SELECT customer_id FROM sales_order WHERE external_order_id='ONLINE418'", Long.class)).isEqualTo(418L);
        assertThat(jdbc.queryForObject("SELECT is_active FROM customer WHERE id=418", Boolean.class)).isFalse();
        mvc.perform(get("/api/v1/customers/418/orders").with(user("418").roles("CASHIER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }
    @Test void concurrentDeactivationCommitsBeforeCheckoutChecksTheCustomer() throws Exception {
        raceWithDeactivation(() -> complete(418L, UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CUSTOMER_INACTIVE"));
        noSale();
    }
    @Test void concurrentCashierProfileEditCannotRestoreTheOldActiveState() throws Exception {
        recordPriorPurchase();
        raceWithDeactivation(() -> mvc.perform(put("/api/v1/customers/418").with(user("418").roles("CASHIER"))
                        .contentType("application/json").content("{\"phone\":\"0900000418\",\"name\":\"Updated by cashier\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        assertThat(jdbc.queryForObject("SELECT is_active FROM customer WHERE id=418", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='REACTIVATE_CUSTOMER'", Long.class)).isZero();
    }
    private ResultActions raceWithDeactivation(Callable<ResultActions> request) throws Exception {
        jdbc.update("UPDATE customer SET is_active=true WHERE id=418");
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var change = pool.submit(() -> transactions.execute(status -> {
                jdbc.update("UPDATE customer SET is_active=false WHERE id=418"); locked.countDown();
                try { if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Checkout did not reach customer lock"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                return null;
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var checkout = pool.submit(request);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE '%customer%')", Boolean.class);
                    if (waiting) break;
                    Thread.sleep(20);
                }
                assertThat(waiting).as("checkout waits for deactivation").isTrue();
                release.countDown(); change.get(10, TimeUnit.SECONDS);
                return checkout.get(10, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
    }
}
