package vn.greenmart.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.PostgresTestImage;

import java.time.*;
import java.util.Map;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "management.endpoint.health.group.readiness.include=readinessState,db,redis"
})
@AutoConfigureMockMvc
@Testcontainers
class AuthApiTest {
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
    static class MutableClock extends Clock {
        volatile Instant now=Instant.now();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    @TestConfiguration static class Time {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    @Autowired PasswordEncoder passwords;
    @Autowired MutableClock clock;
    @Autowired ApplicationContext applicationContext;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    vn.greenmart.identity.repository.RefreshTokenStore refreshStore;

    @BeforeEach void fixtures() {
        clock.now=Instant.now();
        jdbc.execute("TRUNCATE branch,app_user CASCADE");
        try (var connection=redis.getConnectionFactory().getConnection()) { connection.serverCommands().flushDb(); }
        jdbc.update("INSERT INTO branch(id,code,name,is_active) VALUES(1,'GM-001','Branch 1',true),(2,'GM-002','Branch 2',true),(3,'GM-003','Branch 3',false)");
        String hash=passwords.encode("Demo@123");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,full_name,role) VALUES "
                + "(7,'thungan.gm001',?,'Cashier','CASHIER'),(2,'owner',?,'Owner','OWNER'),(1,'admin',?,'Admin','ADMIN')",hash,hash,hash);
        jdbc.update("INSERT INTO user_branch(user_id,branch_id) VALUES(7,1)");
        // Published demo seed hash for user 7; verify compatibility with the actual generator.
        jdbc.update("UPDATE app_user SET password_hash=? WHERE id=7",
                "$2a$10$dUVofiJWY3Br81oBfK2EiuozXgBySz6Aa61yDD1p3/52whS43biAq");
    }
    private JsonNode postJson(String path, Object body, int status, String access) throws Exception {
        var request=post("/api/v1/auth/"+path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        if (access!=null) request.header("Authorization","Bearer "+access);
        var result=mvc.perform(request).andExpect(status().is(status)).andReturn();
        String content=result.getResponse().getContentAsString();
        return content.isEmpty()?json.nullNode():json.readTree(content);
    }
    private JsonNode login() throws Exception { return login("thungan.gm001","Demo@123",200); }
    private JsonNode login(String username,String password,int status) throws Exception {
        return postJson("login",Map.of("username",username,"password",password),status,null);
    }
    private String access(JsonNode tokens) { return tokens.get("accessToken").asText(); }
    private String refresh(JsonNode tokens) { return tokens.get("refreshToken").asText(); }

    @Test void loginAndMeUseRealSignedTokenAndAssignedBranch() throws Exception {
        var tokens=login();
        assertThat(tokens.get("expiresIn").asInt()).isEqualTo(900);
        assertThat(tokens.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(tokens.at("/user/role").asText()).isEqualTo("CASHIER");
        assertThat(tokens.at("/user/branches").size()).isEqualTo(1);
        assertThat(tokens.at("/user/branches/0/code").asText()).isEqualTo("GM-001");
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+access(tokens)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(7)).andExpect(jsonPath("$.branches.length()").value(1));
        var hashes=redis.opsForZSet().range("auth:refresh:{7}",0,-1);
        assertThat(hashes).hasSize(1).allMatch(hash->hash.matches("[0-9a-f]{64}"));
        assertThat(hashes).doesNotContain(refresh(tokens));
        assertThat(redis.getExpire("auth:refresh:{7}")).isBetween(604790L,604800L);
    }
    @Test void refreshRotatesAndRejectsReplay() throws Exception {
        var original=login();
        var rotated=postJson("refresh",Map.of("refreshToken",refresh(original)),200,null);
        assertThat(refresh(rotated)).isNotEqualTo(refresh(original));
        assertThat(access(rotated)).isNotEqualTo(access(original));
        assertThat(postJson("refresh",Map.of("refreshToken",refresh(original)),401,null).get("code").asText()).isEqualTo("INVALID_REFRESH_TOKEN");
        postJson("refresh",Map.of("refreshToken",refresh(rotated)),200,null);
    }
    @Test void fifthFailureLocksAndAuditSurvivesHttp401() throws Exception {
        for (int attempt=1;attempt<=5;attempt++) {
            var error=login("thungan.gm001","wrong",401);
            assertThat(error.get("code").asText()).isEqualTo(attempt==5?"ACCOUNT_LOCKED":"INVALID_CREDENTIALS");
            if (attempt<5) assertThat(error.at("/details/remainingAttempts").asInt()).isEqualTo(5-attempt);
            else assertThat(OffsetDateTime.parse(error.at("/details/lockedUntil").asText()).toInstant())
                    .isEqualTo(clock.instant().plusSeconds(900));
        }
        assertThat(jdbc.queryForObject("SELECT failed_login_count FROM app_user WHERE id=7",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='LOGIN_FAILED'",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='ACCOUNT_LOCKED'",Integer.class)).isEqualTo(1);
        assertThat(login("thungan.gm001","Demo@123",401).get("code").asText()).isEqualTo("ACCOUNT_LOCKED");
        clock.now=clock.now.plusSeconds(901);
        login();
        assertThat(jdbc.queryForObject("SELECT failed_login_count FROM app_user WHERE id=7",Integer.class)).isZero();
        assertThat(jdbc.queryForMap("SELECT locked_until FROM app_user WHERE id=7")).containsEntry("locked_until",null);
    }
    @Test void successfulLoginResetsConsecutiveFailures() throws Exception {
        login("thungan.gm001","wrong",401); login();
        assertThat(login("thungan.gm001","wrong",401).at("/details/remainingAttempts").asInt()).isEqualTo(4);
    }
    @Test void unknownInactiveAndWrongPasswordShareInvalidCredentials() throws Exception {
        assertThat(login("missing","wrong",401).get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(login("thungan.gm001","wrong",401).get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        jdbc.update("UPDATE app_user SET is_active=false WHERE id=7");
        assertThat(login("thungan.gm001","Demo@123",401).get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
    }
    @ParameterizedTest @ValueSource(strings={"eyJhbGciOiJub25lIn0.eyJzdWIiOiI3In0.","invalid.token.value"})
    void rejectsInvalidBearerWithJsonTrace(String token) throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token).header("X-Correlation-ID","trace-241"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").value("trace-241"));
    }
    @Test void wrongSecretAndExpiredAccessAreUnauthorized() throws Exception {
        var forged=new vn.greenmart.identity.security.JwtTokens("other-test-secret-with-at-least-32-bytes",clock);
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+forged.issue(7,"CASHIER")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        var tokens=login(); clock.now=clock.now.plusSeconds(901);
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+access(tokens))).andExpect(status().isUnauthorized());
        postJson("refresh",Map.of("refreshToken",refresh(tokens)),200,access(tokens));
    }
    @Test void missingTokenCannotReachProtectedProductWrite() throws Exception {
        mvc.perform(put("/api/v1/products/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
    @Test void wrongRoleStillReturnsForbiddenAfterRealAuthentication() throws Exception {
        mvc.perform(post("/api/v1/products").header("Authorization","Bearer "+access(login()))
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"sku":"X","name":"X","unit":"hop","categoryId":1,"salePrice":100,"costPrice":1}
                """)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
    @Test void passwordChangeRevokesEverySessionAndUsesBcrypt() throws Exception {
        var a=login(); var b=login();
        postJson("change-password",Map.of("currentPassword","Demo@123","newPassword","Updated@456"),204,access(a));
        for (var tokens:List.of(a,b))
            assertThat(postJson("refresh",Map.of("refreshToken",refresh(tokens)),401,null).get("code").asText()).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(login("thungan.gm001","Demo@123",401).get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        login("thungan.gm001","Updated@456",200);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id=7",String.class)).startsWith("$2a$10$");
    }
    @Test void wrongCurrentPasswordDoesNotRevokeSession() throws Exception {
        var tokens=login();
        assertThat(postJson("change-password",Map.of("currentPassword","wrong","newPassword","Updated@456"),422,access(tokens))
                .get("code").asText()).isEqualTo("WRONG_PASSWORD");
        postJson("refresh",Map.of("refreshToken",refresh(tokens)),200,null);
    }
    @Test void applicationDoesNotCreateDefaultSpringSecurityUser() {
        assertThat(applicationContext.getBeansOfType(InMemoryUserDetailsManager.class)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"short1","onlyletters","12345678"})
    void rejectsWeakNewPassword(String password) throws Exception {
        postJson("change-password",Map.of("currentPassword","Demo@123","newPassword",password),400,access(login()));
    }
    @Test void bcryptByteLimitReturnsValidationWithoutChangingPasswordOrSessions() throws Exception {
        var tokens=login();
        var result=postJson("change-password",Map.of("currentPassword","Demo@123","newPassword","ầ".repeat(30)+"1"),400,access(tokens));
        assertThat(result.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        postJson("refresh",Map.of("refreshToken",refresh(tokens)),200,null);
        login();
    }
    @Test void logoutRevokesOnlyOwnedSession() throws Exception {
        var a=login(); var b=login();
        var owner=login("owner","Demo@123",200);
        assertThat(postJson("logout",Map.of("refreshToken",refresh(owner)),401,access(a)).get("code").asText()).isEqualTo("INVALID_REFRESH_TOKEN");
        postJson("logout",Map.of("refreshToken",refresh(a)),204,access(a));
        postJson("refresh",Map.of("refreshToken",refresh(a)),401,null);
        postJson("refresh",Map.of("refreshToken",refresh(b)),200,null);
        postJson("refresh",Map.of("refreshToken",refresh(owner)),200,null);
    }
    @ParameterizedTest @ValueSource(strings={"owner","admin"})
    void ownersAndAdminsSeeOnlyActiveBranches(String username) throws Exception {
        var tokens=login(username,"Demo@123",200);
        assertThat(tokens.at("/user/branches").size()).isEqualTo(2);
    }
    @Test void expiredRefreshAndDisabledUserCannotRefresh() throws Exception {
        var expired=login(); clock.now=clock.now.plusSeconds(604801);
        postJson("refresh",Map.of("refreshToken",refresh(expired)),401,null);
        clock.now=Instant.now(); var disabled=login();
        jdbc.update("UPDATE app_user SET is_active=false WHERE id=7");
        postJson("refresh",Map.of("refreshToken",refresh(disabled)),401,null);
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+access(disabled))).andExpect(status().isUnauthorized());
    }
    @Test void parallelRefreshHasOneWinner() throws Exception {
        var tokens=login(); var start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            Callable<Integer> action=()->{
                if (!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("refreshToken",refresh(tokens)))))
                        .andReturn().getResponse().getStatus();
            };
            var a=pool.submit(action); var b=pool.submit(action); start.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,401);
        }
        assertThat(redis.opsForZSet().zCard("auth:refresh:{7}")).isEqualTo(1L);
    }
    @Test void parallelWrongPasswordsCannotLoseFailureCounts() throws Exception {
        var start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(5)) {
            var futures=new java.util.ArrayList<Future<String>>();
            for (int i=0;i<5;i++) futures.add(pool.submit(()->{
                if (!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                return login("thungan.gm001","wrong",401).get("code").asText();
            }));
            start.countDown();
            var results=new java.util.ArrayList<String>();
            for(var future:futures) results.add(future.get(20,TimeUnit.SECONDS));
            assertThat(results.stream().filter("ACCOUNT_LOCKED"::equals).count()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT failed_login_count FROM app_user WHERE id=7",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='LOGIN_FAILED'",Integer.class)).isEqualTo(5);
    }
    @Test void concurrentPasswordChangeRevokesAnyRefreshIssuedBeforeItCompletes() throws Exception {
        var tokens=login(); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var rotate=pool.submit(()->{
                if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("refreshToken",refresh(tokens)))))
                        .andReturn().getResponse();
            });
            var change=pool.submit(()->{
                if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                return postJson("change-password",Map.of("currentPassword","Demo@123","newPassword","Updated@456"),204,access(tokens));
            });
            start.countDown();
            var rotated=rotate.get(20,TimeUnit.SECONDS); change.get(20,TimeUnit.SECONDS);
            assertThat(rotated.getStatus()).isIn(200,401);
            if(rotated.getStatus()==200) {
                String newToken=json.readTree(rotated.getContentAsString()).get("refreshToken").asText();
                postJson("refresh",Map.of("refreshToken",newToken),401,null);
            }
        }
        postJson("refresh",Map.of("refreshToken",refresh(tokens)),401,null);
        login("thungan.gm001","Updated@456",200);
    }
    @Test void bcryptBoundaryDoesNotAcceptDifferentSuffixBeyond72Bytes() throws Exception {
        var tokens=login(); String password="a".repeat(71)+"1";
        postJson("change-password",Map.of("currentPassword","Demo@123","newPassword",password),204,access(tokens));
        login("thungan.gm001",password,200);
        assertThat(login("thungan.gm001",password+"extra",401).get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
    }
    @Test void healthIsPublicAndAuthenticatedRequestsDoNotCreateHttpSession() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        var result=mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+access(login())))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getCookie("JSESSIONID")).isNull();
    }
    @Test void redisStorageFailureDoesNotResetLoginCounterOrReturnTokens() throws Exception {
        login("thungan.gm001","wrong",401);
        redis.opsForValue().set("auth:refresh:{7}","wrong-type-for-failure-test");
        var error=postJson("login",Map.of("username","thungan.gm001","password","Demo@123"),500,null);
        assertThat(error.has("accessToken")).isFalse();
        assertThat(error.get("traceId").asText()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT failed_login_count FROM app_user WHERE id=7",Integer.class)).isEqualTo(1);
    }
    @Test void redisRevokeFailureCannotCommitNewPassword() throws Exception {
        var tokens=login();
        String before=jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id=7",String.class);
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("simulated Redis outage"))
                .when(refreshStore).revokeAll(7L);
        var error=postJson("change-password",Map.of("currentPassword","Demo@123","newPassword","Updated@456"),500,access(tokens));
        assertThat(error.get("traceId").asText()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id=7",String.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='CHANGE_PASSWORD'",Integer.class)).isZero();
        postJson("refresh",Map.of("refreshToken",refresh(tokens)),200,null);
    }
    @Test void changedRoleCannotReuseOldAccessToken() throws Exception {
        var tokens=login();
        jdbc.update("UPDATE app_user SET role='STOREKEEPER' WHERE id=7");
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+access(tokens)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(login().at("/user/role").asText()).isEqualTo("STOREKEEPER");
    }
}
