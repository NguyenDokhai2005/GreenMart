package vn.greenmart.promotion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.PostgresTestImage;
import vn.greenmart.sales.service.PromotionCalculator;
import vn.greenmart.sales.service.PosIdempotencyService;
import vn.greenmart.sales.dto.IdempotencyResult;
import vn.greenmart.sales.dto.OrderResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.function.Supplier;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MvcResult;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(username = "42501", roles = "OWNER")
class PromotionQuantityIntegrationTest {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PromotionCalculator calculator;
    @MockitoBean Clock clock;
    @MockitoBean PosIdempotencyService idempotency;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach void fixture() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(idempotency.execute(anyString(),any(),any())).thenAnswer(invocation -> {
            Supplier<OrderResponse> action = invocation.getArgument(2);
            return new IdempotencyResult(true, action.get());
        });
        jdbc.execute("TRUNCATE promotion,audit_log,app_user,branch,category RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO sales_channel(code,name) VALUES('POS','POS'),('WEB','Web'),('MARKET','Market')");
        jdbc.update("INSERT INTO branch(id,code,name,address) VALUES(42501,'GM425','Branch','A')");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,full_name,role) VALUES(42501,'owner425','fixture','Owner','OWNER'),(42502,'cashier425','fixture','Cashier','CASHIER')");
        jdbc.update("INSERT INTO user_branch(user_id,branch_id) VALUES(42502,42501)");
        jdbc.update("INSERT INTO category(id,name) VALUES(42501,'Quantity425')");
        jdbc.update("""
                INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price)
                VALUES(42501,'GM425-A','A','box',42501,10000,5000),(42502,'GM425-B','B','box',42501,10000,5000)
                """);
        jdbc.update("INSERT INTO inventory(branch_id,product_id,on_hand,reserved) VALUES(42501,42501,50,0),(42501,42502,50,0)");
    }

    @Test void itemAmountIsPerUnit() {
        jdbc.update("""
                INSERT INTO promotion(id,code,type,level,value,start_at,end_at,status)
                VALUES(42501,'AMOUNT425','AMOUNT','ITEM',1000,'2029-01-01','2031-01-01','ACTIVE')
                """);
        jdbc.update("INSERT INTO promotion_product(promotion_id,product_id) VALUES(42501,42501)");
        assertThat(calculator.evaluateItemPromotion(42501L,"GM425-A",10000,3,42501L).discount()).isEqualTo(3000);
    }

    @Test void createPersistsAndReturnsMinQuantity() throws Exception {
        mvc.perform(post("/api/v1/promotions").contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"MIN425","type":"AMOUNT","level":"ITEM","value":1000,
                     "startAt":"2030-01-01T00:00:00Z","endAt":"2030-01-02T00:00:00Z",
                     "productIds":[42501],"minQuantity":3}
                    """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.minQuantity").value(3));
        assertThat(jdbc.queryForObject("SELECT min_quantity FROM promotion", Integer.class)).isEqualTo(3);
    }

    @ParameterizedTest
    @CsvSource({"1,AMOUNT,10000,1000,,1000", "3,AMOUNT,10000,1000,,3000", "3,AMOUNT,100000,15000,,45000",
            "3,AMOUNT,10000,15000,,30000", "1,PERCENT,18000,15,6000,2700", "2,PERCENT,18000,15,6000,5400",
            "3,PERCENT,18000,15,6000,6000", "3,PERCENT,10001,33,,9900"})
    void amountPercentAndCapsFollowContract(int qty, String type, long price, long value, Long cap, long discount) {
        insert(42501,type,"ITEM",value,cap,null,NOW.plusSeconds(3600));
        assertThat(calculator.evaluateItemPromotion(42501L,"GM425-A",price,qty,42501L).discount()).isEqualTo(discount);
    }

    @ParameterizedTest @CsvSource({"1,2700,42502", "2,5400,42502", "3,15000,42501"})
    void leadExampleChoosesBestEligibleCampaign(int qty,long discount,long id) {
        insert(42501,"AMOUNT","ITEM",5000,null,3,NOW.plusSeconds(3600));
        insert(42502,"PERCENT","ITEM",15,6000L,null,NOW.plusSeconds(3600));
        var result = calculator.evaluateItemPromotion(42501L,"GM425-A",18000,qty,42501L);
        assertThat(result.id()).isEqualTo(id);
        assertThat(result.discount()).isEqualTo(discount);
    }

    @Test void itemAmountMultiplicationClampsWithoutOverflow() {
        insert(42501,"AMOUNT","ITEM",Long.MAX_VALUE,null,null,NOW.plusSeconds(3600));
        assertThat(calculator.evaluateItemPromotion(42501L,"GM425-A",1,9999,42501L).discount()).isEqualTo(9999);
        assertThat(calculator.evaluateItemPromotion(42501L,"GM425-A",Long.MAX_VALUE,1,42501L).discount()).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> calculator.evaluateItemPromotion(42501L,"GM425-A",Long.MAX_VALUE,2,42501L))
                .isInstanceOf(vn.greenmart.common.exception.AppException.class);
    }

    @Test void tieBreakRemainsEarliestEndThenExistingIdRule() {
        insert(42501,"AMOUNT","ITEM",1000,null,1,NOW.plusSeconds(3600));
        insert(42502,"PERCENT","ITEM",10,null,1,NOW.plusSeconds(1800));
        assertThat(calculator.evaluateItemPromotion(42501L,"GM425-A",10000,3,42501L).id()).isEqualTo(42502);
        jdbc.update("UPDATE promotion SET end_at=?",Timestamp.from(NOW.plusSeconds(3600)));
        assertThat(calculator.evaluateItemPromotion(42501L,"GM425-A",10000,3,42501L).id()).isEqualTo(42501);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "1.5", "1.0", "\"3\"", "true", "2147483648"})
    void invalidThresholdRejectedOnCreateAndUpdateWithoutMutation(String value) throws Exception {
        insert(42501,"AMOUNT","ITEM",1000,null,3,NOW.plusSeconds(3600));
        jdbc.update("UPDATE promotion SET status='PAUSED'");
        var before = jdbc.queryForList("SELECT * FROM promotion");
        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
                post("/api/v1/promotions"), put("/api/v1/promotions/42501")}) {
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body("ITEM",value)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        assertThat(jdbc.queryForList("SELECT * FROM promotion")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log",Integer.class)).isZero();
    }

    @Test void defaultExplicitNullOrderValidationAndLevelChanges() throws Exception {
        mvc.perform(post("/api/v1/promotions").contentType(MediaType.APPLICATION_JSON).content(body("ITEM",null)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.minQuantity").value(1));
        long id = jdbc.queryForObject("SELECT id FROM promotion",Long.class);
        mvc.perform(get("/api/v1/promotions/{id}",id)).andExpect(jsonPath("$.minQuantity").value(1));
        mvc.perform(get("/api/v1/promotions")).andExpect(jsonPath("$.content[0].minQuantity").value(1));
        mvc.perform(put("/api/v1/promotions/{id}",id).contentType(MediaType.APPLICATION_JSON).content(body("ITEM","null")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.minQuantity").value(1));
        mvc.perform(put("/api/v1/promotions/{id}",id).contentType(MediaType.APPLICATION_JSON).content(body("ORDER","1")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].field").value("minQuantity"));
        mvc.perform(put("/api/v1/promotions/{id}",id).contentType(MediaType.APPLICATION_JSON).content(body("ORDER",null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.minQuantity").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT min_quantity FROM promotion",Integer.class)).isNull();
        mvc.perform(put("/api/v1/promotions/{id}",id).contentType(MediaType.APPLICATION_JSON).content(body("ITEM","2147483647")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.minQuantity").value(Integer.MAX_VALUE));
    }

    @Test void orderAmountOnceAndMinOrderUsesTotalAfterItemDiscount() throws Exception {
        insert(42501,"AMOUNT","ITEM",1000,null,null,NOW.plusSeconds(3600));
        insert(42502,"AMOUNT","ORDER",1000,null,null,NOW.plusSeconds(3600));
        jdbc.update("UPDATE promotion SET min_order=28000 WHERE id=42502");
        quote(3,0,27000);
        jdbc.update("UPDATE promotion SET min_order=27000 WHERE id=42502");
        quote(3,0,26000);
        assertThat(calculator.evaluateOrderPromotion(30000,42501L).discount()).isEqualTo(1000);
    }

    @Test void thresholdDoesNotAggregateOtherSkuAndUsageCountsOncePerCampaignPerOrder() throws Exception {
        insert(42501,"AMOUNT","ITEM",500,null,3,NOW.plusSeconds(3600));
        quote(2,1,30000);
        jdbc.update("UPDATE promotion SET min_quantity=1,usage_limit=1");
        quote(2,3,47500);
        mvc.perform(order(2,3,47500)).andExpect(status().isCreated()).andExpect(jsonPath("$.total").value(47500));
        assertThat(jdbc.queryForObject("SELECT used_count FROM promotion",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_item WHERE promotion_id=42501",Integer.class)).isEqualTo(2);
        quote(2,3,50000);
        assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory WHERE product_id=42501",Integer.class)).isEqualTo(48);
        assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory WHERE product_id=42502",Integer.class)).isEqualTo(47);
    }

    @Test void oldExpectedTotalAfterThresholdChangeIsRejectedAtomically() throws Exception {
        insert(42501,"AMOUNT","ITEM",500,null,1,NOW.plusSeconds(3600));
        quote(2,3,47500);
        mvc.perform(post("/api/v1/promotions/42501/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PAUSED\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/promotions/42501").contentType(MediaType.APPLICATION_JSON).content(body("ITEM","4").replace("\"value\":1000","\"value\":500")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/promotions/42501/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        mvc.perform(order(2,3,47500)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TOTAL_CHANGED"));
        for (String table : new String[]{"sales_order","payment","outbox_event","inventory_transaction"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT used_count FROM promotion",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT sum(on_hand) FROM inventory",Long.class)).isEqualTo(100);
    }

    @Test void checkoutWaitingOnCampaignLockRechecksThresholdForEveryAppliedLine() throws Exception {
        insert(42501,"AMOUNT","ITEM",500,null,1,NOW.plusSeconds(3600));
        AtomicReference<Future<MvcResult>> checkout = new AtomicReference<>();
        try (var worker = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactions).execute(tx -> {
                jdbc.queryForList("SELECT id FROM promotion WHERE id=42501 FOR UPDATE");
                jdbc.update("UPDATE promotion SET min_quantity=3 WHERE id=42501");
                // Checkout sees threshold 1 until this transaction commits, then waits for the row lock.
                checkout.set(worker.submit(() -> mvc.perform(order(2,3,47500)).andReturn()));
                await().atMost(Duration.ofSeconds(8)).until(() -> jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%promotion%'",Integer.class)>0);
                return null;
            });
            var response = checkout.get().get(10,TimeUnit.SECONDS).getResponse();
            assertThat(response.getStatus()).isEqualTo(422);
            assertThat(json.readTree(response.getContentAsString()).get("code").asText()).isEqualTo("TOTAL_CHANGED");
        }
        assertThat(jdbc.queryForObject("SELECT used_count FROM promotion",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_order",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT sum(on_hand) FROM inventory",Long.class)).isEqualTo(100);
    }

    private void insert(long id,String type,String level,long value,Long cap,Integer threshold,Instant end) {
        jdbc.update("""
                INSERT INTO promotion(id,code,type,level,value,max_discount,min_quantity,start_at,end_at,status)
                VALUES(?,?,?,?,?,?,?,? ,?,'ACTIVE')
                """,id,"P-"+id,type,level,value,cap,threshold,Timestamp.from(NOW.minusSeconds(60)),Timestamp.from(end));
        if (level.equals("ITEM")) jdbc.update("INSERT INTO promotion_product(promotion_id,product_id) VALUES(?,42501),(?,42502)",id,id);
    }

    private String body(String level,String threshold) {
        return "{\"code\":\"API425\",\"type\":\"AMOUNT\",\"level\":\""+level+"\",\"value\":1000,"
                +"\"startAt\":\"2030-01-01T00:00:00Z\",\"endAt\":\"2030-01-02T00:00:00Z\""
                +(level.equals("ITEM")?",\"productIds\":[42501,42502]":"")
                +(threshold==null?"":",\"minQuantity\":"+threshold)+"}";
    }

    private String items(int a,int b) {
        return "[{\"productId\":42501,\"quantity\":"+a+"}"+(b==0?"":",{\"productId\":42502,\"quantity\":"+b+"}")+"]";
    }

    private void quote(int a,int b,long total) throws Exception {
        mvc.perform(post("/api/v1/orders/quote").with(user("42502").roles("CASHIER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"branchId\":42501,\"items\":"+items(a,b)+"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(total));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder order(int a,int b,long total) {
        return post("/api/v1/orders/pos").with(user("42502").roles("CASHIER")).header("Idempotency-Key","qty-425")
                .contentType(MediaType.APPLICATION_JSON).content("{\"branchId\":42501,\"items\":"+items(a,b)
                        +",\"payment\":{\"method\":\"CASH\",\"cashReceived\":"+total+"},\"expectedTotal\":"+total+"}");
    }
}
