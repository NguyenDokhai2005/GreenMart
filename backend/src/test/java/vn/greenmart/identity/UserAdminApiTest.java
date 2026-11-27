package vn.greenmart.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.identity.repository.RefreshTokenStore;
import vn.greenmart.inventory.PostgresTestImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={
        "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "management.endpoint.health.group.readiness.include=readinessState,db,redis",
        "greenmart.outbox.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class UserAdminApiTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(PostgresTestImage.IMAGE);
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.6-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.forecast_user",()->"forecast_ro");
        registry.add("spring.data.redis.host",REDIS::getHost);
        registry.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password",()->"");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    @Autowired PasswordEncoder passwords;
    @MockitoSpyBean RefreshTokenStore sessions;
    private String admin;

    @BeforeEach void fixtures() throws Exception {
        jdbc.execute("TRUNCATE branch,app_user CASCADE");
        try(var connection=redis.getConnectionFactory().getConnection()) { connection.serverCommands().flushDb(); }
        jdbc.update("INSERT INTO branch(id,code,name,is_active) VALUES(1,'GM-001','First',true),(2,'GM-002','Second',true),(3,'GM-003','Inactive',false)");
        String hash=passwords.encode("Demo@123");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,full_name,role) VALUES"
                +"(1,'admin',?,'Admin','ADMIN'),(2,'owner',?,'Owner','OWNER'),(7,'cashier',?,'Cashier','CASHIER')",hash,hash,hash);
        jdbc.execute("SELECT setval(pg_get_serial_sequence('app_user','id'),100)");
        jdbc.update("INSERT INTO user_branch(user_id,branch_id) VALUES(7,1)");
        admin=login("admin").path("accessToken").asText();
    }
    @AfterEach void removeFault() { jdbc.execute("DROP FUNCTION IF EXISTS issue424_fault() CASCADE"); }
    private JsonNode request(MockHttpServletRequestBuilder request, Object body, String access, int expected) throws Exception {
        if(access!=null) request.header("Authorization","Bearer "+access);
        if(body!=null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        String response=mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isBlank()?json.nullNode():json.readTree(response);
    }
    private JsonNode login(String username) throws Exception {
        return request(post("/api/v1/auth/login"),Map.of("username",username,"password","Demo@123"),null,200);
    }
    private Map<String,Object> create(String username, String role, List<Long> branches) {
        return Map.of("username",username,"fullName","New User","password","Demo@123","role",role,"branchIds",branches);
    }
    private Map<String,Object> update(String role, boolean active, List<Long> branches) {
        return Map.of("fullName","Updated","role",role,"active",active,"branchIds",branches);
    }
    private void assertRevoked(JsonNode issued) throws Exception {
        request(get("/api/v1/auth/me"),null,issued.path("accessToken").asText(),401);
        request(post("/api/v1/auth/refresh"),Map.of("refreshToken",issued.path("refreshToken").asText()),null,401);
    }

    @Test void createGetListUsesContractAndNeverReturnsCredentials() throws Exception {
        var created=request(post("/api/v1/users"),create("new.cashier","CASHIER",List.of(1L,1L)),admin,201);
        long id=created.path("id").asLong();
        assertThat(created.path("branches").size()).isEqualTo(1);
        assertThat(created.path("locked").asBoolean()).isFalse();
        assertThat(created.toString()).doesNotContain("password","passwordHash","password_hash","Demo@123");
        String hash=jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id=?",String.class,id);
        assertThat(passwords.matches("Demo@123",hash)).isTrue();
        assertThat(request(get("/api/v1/users/"+id),null,admin,200).path("username").asText()).isEqualTo("new.cashier");
        var found=request(get("/api/v1/users").param("q","NEW.CASHIER").param("role","CASHIER").param("branchId","1")
                .param("active","true").param("size","1").param("sort","username,desc"),null,admin,200);
        assertThat(found.path("totalElements").asInt()).isEqualTo(1);
        assertThat(found.path("content").get(0).path("id").asLong()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='CREATE_USER' AND entity_id=?",Integer.class,Long.toString(id))).isEqualTo(1);
        request(get("/api/v1/users/9999"),null,admin,404);
    }
    @Test void globalRolesIgnoreMembershipAndSeeActiveBranches() throws Exception {
        var owner=request(post("/api/v1/users"),create("new.owner","OWNER",List.of(9999L)),admin,201);
        assertThat(owner.path("branches").size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_branch WHERE user_id=?",Integer.class,owner.path("id").asLong())).isZero();
        var all=request(get("/api/v1/users").param("branchId","2"),null,admin,200);
        assertThat(all.path("totalElements").asInt()).isEqualTo(3); // Admin + two Owners, not branch-1 Cashier.
    }
    @ParameterizedTest @ValueSource(strings={"owner","cashier"})
    void ownerCanOnlyReadAndCashierCannotManageUsers(String username) throws Exception {
        String access=login(username).path("accessToken").asText();
        request(get("/api/v1/users"),null,null,401);
        request(get("/api/v1/users"),null,access,username.equals("owner") ? 200 : 403);
        request(get("/api/v1/users/7"),null,access,username.equals("owner") ? 200 : 403);
        request(post("/api/v1/users"),Map.of(),access,403);
        request(put("/api/v1/users/7"),Map.of(),access,403);
        request(post("/api/v1/users/7/unlock"),null,access,403);
        request(post("/api/v1/users/7/reset-password"),Map.of(),access,403);
    }
    @ParameterizedTest @ValueSource(strings={"CASHIER","STOREKEEPER","BRANCH_MANAGER"})
    void scopedRolesRequireExistingActiveBranches(String role) throws Exception {
        for(var branches:List.of(List.<Long>of(),List.of(3L),List.of(9999L),List.of(-1L)))
            request(post("/api/v1/users"),create("invalid",role,branches),admin,400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE username='invalid'",Integer.class)).isZero();
    }
    @Test void validationAndLiteralSearchAndStablePagination() throws Exception {
        request(post("/api/v1/users"),Map.of(),admin,400);
        request(post("/api/v1/users"),create("INVALID SPACE","ADMIN",List.of()),admin,400);
        var invalid=new java.util.HashMap<>(create("valid","CASHIER",List.of(1L)));
        invalid.put("password","password"); request(post("/api/v1/users"),invalid,admin,400);
        invalid.put("password","é".repeat(36)+"A1"); request(post("/api/v1/users"),invalid,admin,400);
        assertThat(request(post("/api/v1/users"),create("admin","ADMIN",List.of()),admin,409)
                .path("code").asText()).isEqualTo("DUPLICATE_USERNAME");
        for(var query:List.of(Map.of("page","-1"),Map.of("size","101"),Map.of("sort","password_hash,asc"),Map.of("role","BAD"))) {
            var call=get("/api/v1/users"); query.forEach(call::param); request(call,null,admin,400);
        }
        assertThat(request(get("/api/v1/users").param("q","%"),null,admin,200).path("totalElements").asInt()).isZero();
        var first=request(get("/api/v1/users").param("size","1"),null,admin,200);
        var second=request(get("/api/v1/users").param("size","1").param("page","1"),null,admin,200);
        assertThat(first.path("totalPages").asInt()).isEqualTo(3);
        assertThat(first.path("content").get(0).path("id")).isNotEqualTo(second.path("content").get(0).path("id"));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void duplicateUsernameDoesNotChangeExistingAccountMembershipOrAudit(boolean active) throws Exception {
        jdbc.update("UPDATE app_user SET is_active=? WHERE id=7",active);
        var existing=jdbc.queryForMap("SELECT full_name,role,is_active,password_hash FROM app_user WHERE id=7");
        int accounts=jdbc.queryForObject("SELECT count(*) FROM app_user",Integer.class);
        int auditCount=jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='CREATE_USER'",Integer.class);
        assertThat(request(post("/api/v1/users"),create("cashier","OWNER",List.of()),admin,409)
                .path("code").asText()).isEqualTo("DUPLICATE_USERNAME");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user",Integer.class)).isEqualTo(accounts);
        assertThat(jdbc.queryForMap("SELECT full_name,role,is_active,password_hash FROM app_user WHERE id=7")).isEqualTo(existing);
        assertThat(jdbc.queryForList("SELECT branch_id FROM user_branch WHERE user_id=7",Long.class)).containsExactly(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='CREATE_USER'",Integer.class)).isEqualTo(auditCount);
    }

    @Test void concurrentDuplicateUsernameCreatesExactlyOneAccountAndAudit() throws Exception {
        var start=new CountDownLatch(1);
        byte[] body=json.writeValueAsBytes(create("concurrent.user","CASHIER",List.of(1L)));
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<MockHttpServletResponse> createRequest=()-> {
                start.await();
                return mvc.perform(post("/api/v1/users").header("Authorization","Bearer "+admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
            };
            var first=executor.submit(createRequest);
            var second=executor.submit(createRequest);
            start.countDown();
            var responses=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
            assertThat(responses).extracting(MockHttpServletResponse::getStatus)
                    .containsExactlyInAnyOrder(201,409);
            var rejected=responses.stream().filter(response->response.getStatus()==409).findFirst().orElseThrow();
            assertThat(json.readTree(rejected.getContentAsString()).path("code").asText()).isEqualTo("DUPLICATE_USERNAME");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE username='concurrent.user'",Integer.class)).isEqualTo(1);
        long id=jdbc.queryForObject("SELECT id FROM app_user WHERE username='concurrent.user'",Long.class);
        assertThat(jdbc.queryForList("SELECT branch_id FROM user_branch WHERE user_id=?",Long.class,id)).containsExactly(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='CREATE_USER' AND entity_id=?",
                Integer.class,Long.toString(id))).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"role","branches","active"})
    void accessChangesRevokeAllExistingSessionsPermanently(String change) throws Exception {
        var issued=login("cashier"); var another=login("cashier");
        request(put("/api/v1/users/7"),update("role".equals(change)?"STOREKEEPER":"CASHIER",!"active".equals(change),
                List.of("branches".equals(change)?2L:1L)),admin,200);
        assertRevoked(issued); assertRevoked(another);
        if("active".equals(change)) {
            request(put("/api/v1/users/7"),update("CASHIER",true,List.of(1L)),admin,200);
            assertRevoked(issued);
        }
    }
    @Test void nameOnlyEditKeepsSessionsAndSelfDemotionIsRejected() throws Exception {
        var issued=login("cashier");
        request(put("/api/v1/users/7"),update("CASHIER",true,List.of(1L)),admin,200);
        request(get("/api/v1/auth/me"),null,issued.path("accessToken").asText(),200);
        for(var change:List.of(update("ADMIN",false,List.of()),update("OWNER",true,List.of()))) {
            var error=request(put("/api/v1/users/1"),change,admin,422);
            assertThat(error.path("code").asText()).isEqualTo("SELF_ACTION_FORBIDDEN");
        }
    }
    @Test void unlockClearsTemporaryLockWithoutReactivatingDisabledAccount() throws Exception {
        jdbc.update("UPDATE app_user SET is_active=false,failed_login_count=5,locked_until=? WHERE id=7",OffsetDateTime.now().plusMinutes(15));
        assertThat(request(get("/api/v1/users/7"),null,admin,200).path("locked").asBoolean()).isTrue();
        request(post("/api/v1/users/7/unlock"),null,admin,204);
        var user=request(get("/api/v1/users/7"),null,admin,200);
        assertThat(user.path("locked").asBoolean()).isFalse(); assertThat(user.path("active").asBoolean()).isFalse();
    }
    @Test void resetPasswordRevokesAccessAndRefreshAndDoesNotLeakPasswordInAudit() throws Exception {
        var issued=login("cashier");
        request(post("/api/v1/users/7/reset-password"),Map.of("newPassword","Newpass123"),admin,204);
        assertRevoked(issued);
        request(post("/api/v1/auth/login"),Map.of("username","cashier","password","Demo@123"),null,401);
        request(post("/api/v1/auth/login"),Map.of("username","cashier","password","Newpass123"),null,200);
        assertThat(jdbc.queryForObject("SELECT detail::text FROM audit_log WHERE action='RESET_USER_PASSWORD'",String.class))
                .doesNotContain("Newpass123","password_hash");
    }
    @ParameterizedTest @ValueSource(strings={"update","reset-password"})
    void redisFailurePreventsAccountChanges(String action) throws Exception {
        String oldHash=jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id=7",String.class);
        doThrow(new AppException(ErrorCode.AUTH_UNAVAILABLE)).when(sessions).revokeAll(7L);
        if("update".equals(action)) request(put("/api/v1/users/7"),update("OWNER",false,List.of()),admin,503);
        else request(post("/api/v1/users/7/reset-password"),Map.of("newPassword","Newpass123"),admin,503);
        assertThat(jdbc.queryForMap("SELECT role,is_active FROM app_user WHERE id=7"))
                .containsEntry("role","CASHIER").containsEntry("is_active",true);
        assertThat(jdbc.queryForObject("SELECT branch_id FROM user_branch WHERE user_id=7",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id=7",String.class)).isEqualTo(oldHash);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action IN ('UPDATE_USER','RESET_USER_PASSWORD')",Integer.class)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"update","reset-password"})
    void auditFailureRollsBackAccountButCannotRestoreRevokedSessions(String action) throws Exception {
        var issued=login("cashier");
        String oldHash=jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id=7",String.class);
        jdbc.execute("CREATE FUNCTION issue424_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action IN ('UPDATE_USER','RESET_USER_PASSWORD') THEN RAISE EXCEPTION 'test-only audit failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER issue424_fault BEFORE INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION issue424_fault()");
        if("update".equals(action)) request(put("/api/v1/users/7"),update("STOREKEEPER",false,List.of(2L)),admin,500);
        else request(post("/api/v1/users/7/reset-password"),Map.of("newPassword","Newpass123"),admin,500);
        assertThat(jdbc.queryForMap("SELECT role,is_active,full_name FROM app_user WHERE id=7"))
                .containsEntry("role","CASHIER").containsEntry("is_active",true).containsEntry("full_name","Cashier");
        assertThat(jdbc.queryForObject("SELECT branch_id FROM user_branch WHERE user_id=7",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id=7",String.class)).isEqualTo(oldHash);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action IN ('UPDATE_USER','RESET_USER_PASSWORD')",Integer.class)).isZero();
        assertRevoked(issued);
        login("cashier"); // The original password still works after DB rollback.
    }
    @Test void auditFailureRollsBackCreationAndMembership() throws Exception {
        jdbc.execute("CREATE FUNCTION issue424_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='CREATE_USER' THEN RAISE EXCEPTION 'test-only audit failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER issue424_fault BEFORE INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION issue424_fault()");
        request(post("/api/v1/users"),create("rollback","CASHIER",List.of(1L)),admin,500);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE username='rollback'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_branch",Integer.class)).isEqualTo(1);
    }
    @Test void concurrentCrossDemotionCannotRemoveAllAdmins() throws Exception {
        jdbc.update("UPDATE app_user SET role='ADMIN' WHERE id=2");
        String other=login("owner").path("accessToken").asText();
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()-> { start.await(); return mvc.perform(put("/api/v1/users/2").header("Authorization","Bearer "+admin)
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(update("OWNER",true,List.of())))).andReturn().getResponse().getStatus(); });
            var b=executor.submit(()-> { start.await(); return mvc.perform(put("/api/v1/users/1").header("Authorization","Bearer "+other)
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(update("OWNER",true,List.of())))).andReturn().getResponse().getStatus(); });
            start.countDown();
            var results=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
            assertThat(results).contains(200); assertThat(results).anyMatch(code->code==401 || code==403);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE is_active AND role='ADMIN'",Integer.class)).isEqualTo(1);
    }
}
