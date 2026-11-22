package vn.greenmart.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.integration.channel.ChannelSignatures;
import vn.greenmart.inventory.PostgresTestImage;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties={
        "spring.autoconfigure.exclude=org.springframework.boot.data.redis.autoconfigure.RedisAutoConfiguration,org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "management.endpoint.health.group.readiness.include=readinessState,db",
        "management.health.redis.enabled=false", "greenmart.outbox.enabled=false", "greenmart.order.expiry.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class ChannelEventSecurityTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    vn.greenmart.identity.repository.RefreshTokenStore sessions;
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>(PostgresTestImage.IMAGE);
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);
        r.add("spring.datasource.username",DB::getUsername);
        r.add("spring.datasource.password",DB::getPassword);
        r.add("spring.flyway.placeholders.forecast_user",()->"forecast_ro");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired ChannelSignatures signatures;

    @BeforeEach void fixtures() {
        jdbc.execute("TRUNCATE branch,category,app_user,promotion CASCADE");
        jdbc.execute("TRUNCATE audit_log,outbox_event");
        jdbc.update("INSERT INTO branch(id,code,name) VALUES(416,'GM-416','Security')");
        jdbc.update("INSERT INTO category(id,name) VALUES(416,'Security')");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,full_name,role) VALUES(416,'manager416','hash','Manager','BRANCH_MANAGER')");
        jdbc.update("INSERT INTO user_branch(user_id,branch_id) VALUES(416,416)");
        jdbc.update("INSERT INTO sales_channel(code,name,webhook_secret) VALUES('WEB','Web','test-only-web-416'),('MARKET','Market','test-only-market-416')");
        jdbc.update("INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price) VALUES(416,'SKU416','Test','hop',416,100,50)");
        jdbc.update("INSERT INTO inventory(branch_id,product_id,on_hand,reserved) VALUES(416,416,100,0)");
    }
    @AfterEach void removeFault() { jdbc.execute("DROP FUNCTION IF EXISTS issue416_fault() CASCADE"); }
    private long order(String channel,String externalId) {
        long id=jdbc.queryForObject("""
                INSERT INTO sales_order(code,channel_id,external_order_id,branch_id,status,subtotal,total,reserved_until)
                SELECT ?,id,?,416,'RESERVED',100,100,NOW()+INTERVAL '10 minutes' FROM sales_channel WHERE code=? RETURNING id
                """,Long.class,channel+"-"+externalId,externalId,channel);
        jdbc.update("INSERT INTO order_item(order_id,product_id,quantity,unit_price,line_total) VALUES(?,416,1,100,100)",id);
        jdbc.update("UPDATE inventory SET reserved=reserved+1 WHERE branch_id=416 AND product_id=416");
        return id;
    }
    private String body(String ref) throws Exception {
        // Business time is historical; only transport time participates in freshness checks.
        return json.writeValueAsString(Map.of("type","PAID","occurredAt","2026-10-01T10:00:00+07:00","providerRef",ref));
    }
    private String path(String channel,String id) { return "/api/v1/integrations/"+channel+"/orders/"+id+"/events"; }
    private HttpHeaders headers(String channel,String id,String body,String eventId,long timestamp) {
        return SignedChannelEvents.headers(signatures,channel,path(channel,id),body,eventId,Long.toString(timestamp));
    }
    private HttpHeaders headers(String channel,String id,String body) {
        return headers(channel,id,body,UUID.randomUUID().toString(),Instant.now().getEpochSecond());
    }
    private MockHttpServletResponse send(String channel,String id,String body,HttpHeaders headers) throws Exception {
        return mvc.perform(post(path(channel,id)).headers(headers).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }
    private void expect(MockHttpServletResponse response,int status,String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        if(code!=null) assertThat(json.readTree(response.getContentAsString()).path("code").asText()).isEqualTo(code);
    }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class); }
    private String status(long id) { return jdbc.queryForObject("SELECT status FROM sales_order WHERE id=?",String.class,id); }

    @Test void originalBodyOnlySignatureAndMalformedHeadersAreRejected() throws Exception {
        long id=order("WEB","A"); String body=body("REF-A");
        var old=new HttpHeaders(); old.set("X-Signature",signatures.sign("WEB",body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        expect(send("WEB","A",body,old),401,"INVALID_SIGNATURE");
        for(String value:new String[]{"bad","1","00000000-0000-0000-0000-00000000000x"}) {
            var h=headers("WEB","A",body); h.set("X-Event-ID",value);
            expect(send("WEB","A",body,h),401,"INVALID_SIGNATURE");
        }
        var h=headers("WEB","A",body); h.set("X-Event-Timestamp","999999999999999999999");
        expect(send("WEB","A",body,h),401,"INVALID_SIGNATURE");
        assertThat(status(id)).isEqualTo("RESERVED"); assertThat(count("payment")).isZero();
    }
    @Test void signatureBindsOrderChannelBodyTimestampAndEventId() throws Exception {
        order("WEB","A"); order("WEB","B"); order("MARKET","A");
        jdbc.update("UPDATE sales_channel SET webhook_secret='test-only-web-416' WHERE code='MARKET'");
        String body=body("REF-A"); var h=headers("WEB","A",body);
        expect(send("WEB","B",body,h),401,"INVALID_SIGNATURE");
        expect(send("MARKET","A",body,h),401,"INVALID_SIGNATURE");
        expect(send("WEB","A",body("REF-CHANGED"),h),401,"INVALID_SIGNATURE");
        var changed=new HttpHeaders(h); changed.set("X-Event-Timestamp",Long.toString(Instant.now().getEpochSecond()+1));
        expect(send("WEB","A",body,changed),401,"INVALID_SIGNATURE");
        changed=new HttpHeaders(h); changed.set("X-Event-ID",UUID.randomUUID().toString());
        expect(send("WEB","A",body,changed),401,"INVALID_SIGNATURE");
        assertThat(count("payment")).isZero(); assertThat(count("channel_event_receipt")).isZero();
    }
    @ParameterizedTest @ValueSource(longs={-600,600})
    void staleOrFutureTransportTimeIsRejectedEvenWithCorrectSignature(long delta) throws Exception {
        order("WEB","A"); String body=body("REF-A");
        expect(send("WEB","A",body,headers("WEB","A",body,UUID.randomUUID().toString(),Instant.now().getEpochSecond()+delta)),401,"INVALID_SIGNATURE");
        assertThat(count("payment")).isZero();
    }
    @Test void exactRetryReturnsOriginalAckAfterCancellationWithoutReapplyingPayment() throws Exception {
        long id=order("WEB","A"); String body=body("REF-A"); String eventId=UUID.randomUUID().toString();
        var first=send("WEB","A",body,headers("WEB","A",body,eventId,Instant.now().getEpochSecond()));
        expect(first,200,null);
        var cancelled=mvc.perform(post("/api/v1/orders/{id}/cancel",id).with(user("416").roles("BRANCH_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"CUSTOMER_REQUEST\"}")).andReturn().getResponse();
        expect(cancelled,200,null);
        long audit=count("audit_log"),outbox=count("outbox_event");
        jdbc.update("UPDATE channel_event_receipt SET created_at=NOW()-INTERVAL '8 days'");
        var replay=send("WEB","A",body,headers("WEB","A",body,eventId,Instant.now().getEpochSecond()+1));
        expect(replay,200,null); assertThat(json.readTree(replay.getContentAsString())).isEqualTo(json.readTree(first.getContentAsString()));
        assertThat(status(id)).isEqualTo("CANCELLED"); assertThat(count("payment")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE order_id=?",String.class,id)).isEqualTo("REFUNDED");
        assertThat(count("audit_log")).isEqualTo(audit); assertThat(count("outbox_event")).isEqualTo(outbox);
        order("WEB","B"); expect(send("WEB","B",body,headers("WEB","B",body)),409,"CONFLICT");
    }
    @Test void sameEventIdWithDifferentSignedTargetOrBodyConflicts() throws Exception {
        order("WEB","A"); long second=order("WEB","B"); String body=body("REF-A"); String eventId=UUID.randomUUID().toString();
        expect(send("WEB","A",body,headers("WEB","A",body,eventId,Instant.now().getEpochSecond())),200,null);
        expect(send("WEB","B",body,headers("WEB","B",body,eventId,Instant.now().getEpochSecond())),409,"CONFLICT");
        String changed=body("REF-OTHER");
        expect(send("WEB","A",changed,headers("WEB","A",changed,eventId,Instant.now().getEpochSecond())),409,"CONFLICT");
        assertThat(status(second)).isEqualTo("RESERVED"); assertThat(count("payment")).isEqualTo(1);
    }
    @Test void referencesAreUniqueAcrossChannelsSharingTheGatewayAndImmutableOnAnOrder() throws Exception {
        order("WEB","A"); long second=order("MARKET","B"); String body=body("REF-A");
        expect(send("WEB","A",body,headers("WEB","A",body)),200,null);
        expect(send("MARKET","B",body,headers("MARKET","B",body)),409,"CONFLICT");
        String changed=body("REF-OTHER"); expect(send("WEB","A",changed,headers("WEB","A",changed)),409,"CONFLICT");
        assertThat(status(second)).isEqualTo("RESERVED"); assertThat(count("channel_payment_reference")).isEqualTo(1);
        // A genuinely different gateway/merchant may issue the same opaque reference.
        jdbc.update("UPDATE sales_channel SET payment_provider_scope='OTHER:MERCHANT' WHERE code='MARKET'");
        expect(send("MARKET","B",body,headers("MARKET","B",body)),200,null);
        assertThat(count("payment")).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"", " "})
    void paidRequiresNonblankProviderReference(String ref) throws Exception {
        order("WEB","A"); String body=body(ref);
        expect(send("WEB","A",body,headers("WEB","A",body)),400,"VALIDATION_ERROR");
        assertThat(count("channel_event_receipt")).isZero(); assertThat(count("payment")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"same-event","different-events","different-orders"})
    void concurrentEventsRemainAtomicWithoutDuplicatePaymentsOrDeadlocks(String mode) throws Exception {
        long a=order("WEB","A"),b=order("WEB","B"); String body=body("REF-A");
        var firstHeaders=headers("WEB","A",body);
        String target="different-orders".equals(mode)?"B":"A";
        var secondHeaders="same-event".equals(mode)?firstHeaders:headers("WEB",target,body);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<MockHttpServletResponse> first=()->{start.await();return send("WEB","A",body,firstHeaders);};
            Callable<MockHttpServletResponse> second=()->{start.await();return send("WEB",target,body,secondHeaders);};
            var one=pool.submit(first); var two=pool.submit(second); start.countDown();
            var responses=List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));
            assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200,
                    "different-orders".equals(mode)?409:200);
            for(var response:responses) if(response.getStatus()==409) expect(response,409,"CONFLICT");
        }
        assertThat(count("payment")).isEqualTo(1); assertThat(count("channel_payment_reference")).isEqualTo(1);
        assertThat(count("channel_event_receipt")).isEqualTo("different-events".equals(mode)?2:1);
        assertThat(List.of(status(a),status(b))).containsExactlyInAnyOrder("CONFIRMED","RESERVED");
    }
    @ParameterizedTest @ValueSource(strings={"audit_log","outbox_event"})
    void downstreamFailureRollsBackPaymentOrderReceiptAndReferenceThenAllowsRetry(String table) throws Exception {
        long id=order("WEB","A"); String body=body("REF-A"); var h=headers("WEB","A",body);
        jdbc.execute("CREATE FUNCTION issue416_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test-only 416 rollback'; END $$");
        jdbc.execute("CREATE TRIGGER issue416_fault BEFORE INSERT ON "+table+" FOR EACH ROW EXECUTE FUNCTION issue416_fault()");
        expect(send("WEB","A",body,h),500,"INTERNAL_SERVER_ERROR");
        assertThat(status(id)).isEqualTo("RESERVED");
        for(String name:List.of("payment","channel_payment_reference","channel_event_receipt","outbox_event","audit_log")) assertThat(count(name)).isZero();
        removeFault(); expect(send("WEB","A",body,h),200,null);
    }
    @Test void databaseGuardAlsoRejectsAWriterBypassingTheWebhook() throws Exception {
        order("WEB","A"); long b=order("WEB","B"); String body=body("REF-A");
        expect(send("WEB","A",body,headers("WEB","A",body)),200,null);
        assertThatThrownBy(()->jdbc.update("INSERT INTO payment(order_id,method,amount,status,provider_ref) VALUES(?,'QR',100,'PAID','REF-A')",b))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("payment")).isEqualTo(1); assertThat(status(b)).isEqualTo("RESERVED");
    }

    @Test void signedCancellationDoesNotRequireAProviderReferenceAndIsIdempotent() throws Exception {
        long id=order("WEB","A");
        String body="{\"type\":\"CANCELLED\",\"occurredAt\":\"2026-10-01T00:00:00Z\"}";
        var h=headers("WEB","A",body);
        expect(send("WEB","A",body,h),200,null);
        long audit=count("audit_log"),outbox=count("outbox_event");
        expect(send("WEB","A",body,h),200,null);
        assertThat(status(id)).isEqualTo("CANCELLED"); assertThat(count("payment")).isZero();
        assertThat(count("audit_log")).isEqualTo(audit); assertThat(count("outbox_event")).isEqualTo(outbox);
        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory WHERE branch_id=416 AND product_id=416",Integer.class)).isZero();
    }
    @Test void invalidSignedJsonOrMissingPaidReferenceNeverPersistsAReceipt() throws Exception {
        order("WEB","A");
        for(String body:List.of("null","{}","{} {}","{\"type\":\"PAID\",\"occurredAt\":\"2026-10-01T00:00:00Z\"}")) {
            expect(send("WEB","A",body,headers("WEB","A",body)),400,"VALIDATION_ERROR");
        }
        assertThat(count("channel_event_receipt")).isZero(); assertThat(count("channel_payment_reference")).isZero();
    }
    @Test void rawPercentEncodedPathMatchesTheConnectorSignature() throws Exception {
        long id=order("WEB","A B"); String body=body("REF-ENCODED");
        var h=headers("WEB","A%20B",body);
        var response=mvc.perform(post(java.net.URI.create(path("WEB","A%20B")))
                .headers(h).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        expect(response,200,null); assertThat(status(id)).isEqualTo("CONFIRMED");
    }
}
