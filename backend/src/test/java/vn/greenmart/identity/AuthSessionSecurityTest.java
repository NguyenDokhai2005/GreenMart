package vn.greenmart.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class AuthSessionSecurityTest {
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

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    vn.greenmart.identity.repository.AuthUserRepository users;

    @Test void logoutWorksWithExpiredAccessAndTemporaryLockAndRevokesAccessImmediately() throws Exception {
        var issued=login();
        clock.now=clock.now.plusSeconds(901);
        jdbc.update("UPDATE app_user SET failed_login_count=5,locked_until=? WHERE id=7",OffsetDateTime.now(clock).plusMinutes(15));
        postJson("logout",Map.of("refreshToken",refresh(issued)),204,access(issued));
        clock.now=clock.now.plusSeconds(901);
        postJson("refresh",Map.of("refreshToken",refresh(issued)),401,null);
    }
    @Test void logoutRevokesOnlyItsSessionAndBothAccessGenerations() throws Exception {
        var first=login(); var other=login();
        var rotated=postJson("refresh",Map.of("refreshToken",refresh(first)),200,null);
        postJson("logout",Map.of("refreshToken",refresh(first)),204,null);
        for(var token:List.of(first,rotated)) {
            mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+access(token))).andExpect(status().isUnauthorized());
            postJson("refresh",Map.of("refreshToken",refresh(token)),401,null);
        }
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+access(other))).andExpect(status().isOk());
        postJson("refresh",Map.of("refreshToken",refresh(other)),200,null);
    }
    @Test void repeatedLogoutAndUnknownWellFormedTokenAreIdempotent() throws Exception {
        var issued=login();
        postJson("logout",Map.of("refreshToken",refresh(issued)),204,null);
        postJson("logout",Map.of("refreshToken",refresh(issued)),204,null);
        postJson("logout",Map.of("refreshToken","7."+"A".repeat(43)),204,null);
    }
    @Test void fakeRefreshIsRejectedWithoutAnyAccountDatabaseCall() throws Exception {
        org.mockito.Mockito.clearInvocations(users);
        for(int attempt=0;attempt<25;attempt++) postJson("refresh",Map.of("refreshToken","7."+"A".repeat(43)),401,null);
        org.mockito.Mockito.verifyNoInteractions(users);
    }
    @Test void refreshFloodIsLimitedBeforeDatabaseAndMalformedBodyParsing() throws Exception {
        for(int attempt=0;attempt<60;attempt++) postJson("refresh",Map.of("refreshToken","7."+"A".repeat(43)),401,null);
        org.mockito.Mockito.clearInvocations(users);
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("REFRESH_RATE_LIMITED"));
        org.mockito.Mockito.verifyNoInteractions(users);
    }
    @Test void passwordVerificationStopsAtFiveAttemptsAcrossSessions() throws Exception {
        var issued=login(); var other=login();
        for(int attempt=0;attempt<5;attempt++) {
            postJson("change-password",Map.of("currentPassword","wrong","newPassword","Replacement@123"),422,access(issued));
        }
        org.mockito.Mockito.clearInvocations(users);
        mvc.perform(post("/api/v1/auth/change-password").header("Authorization","Bearer "+access(other))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("currentPassword","Demo@123","newPassword","Replacement@123"))))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("PASSWORD_RATE_LIMITED"));
        org.mockito.Mockito.verify(users,org.mockito.Mockito.never()).find(7L,true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='CHANGE_PASSWORD_FAILED'",Integer.class)).isEqualTo(5);
    }
    @Test void passwordChangeRevokesExistingAccessAsWellAsRefresh() throws Exception {
        var first=login(); var second=login();
        postJson("change-password",Map.of("currentPassword","Demo@123","newPassword","Updated@456"),204,access(first));
        for(var issued:List.of(first,second)) {
            mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+access(issued))).andExpect(status().isUnauthorized());
            postJson("refresh",Map.of("refreshToken",refresh(issued)),401,null);
        }
        login("thungan.gm001","Updated@456",200);
    }
    @Test void concurrentRefreshCannotSurviveLogoutOfItsAncestor() throws Exception {
        var issued=login(); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var rotating=pool.submit(()-> {
                if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("refreshToken",refresh(issued))))).andReturn().getResponse();
            });
            var loggingOut=pool.submit(()-> {
                if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                return postJson("logout",Map.of("refreshToken",refresh(issued)),204,null);
            });
            start.countDown();
            var rotated=rotating.get(20,TimeUnit.SECONDS); loggingOut.get(20,TimeUnit.SECONDS);
            assertThat(rotated.getStatus()).isIn(200,401);
            if(rotated.getStatus()==200) {
                var pair=json.readTree(rotated.getContentAsString());
                postJson("refresh",Map.of("refreshToken",refresh(pair)),401,null);
                mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+access(pair))).andExpect(status().isUnauthorized());
            }
        }
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+access(issued))).andExpect(status().isUnauthorized());
    }

    @Test void disabledAccountCanRevokeItsSessionWithoutAccessAuthentication() throws Exception {
        var issued=login();
        jdbc.update("UPDATE app_user SET is_active=false WHERE id=7");
        postJson("logout",Map.of("refreshToken",refresh(issued)),204,"not-a-jwt");
        jdbc.update("UPDATE app_user SET is_active=true WHERE id=7");
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+access(issued))).andExpect(status().isUnauthorized());
        postJson("refresh",Map.of("refreshToken",refresh(issued)),401,null);
    }
    @Test void newLoginCannotResurrectSessionsRevokedByPasswordChange() throws Exception {
        var issued=login();
        postJson("change-password",Map.of("currentPassword","Demo@123","newPassword","Updated@456"),204,access(issued));
        var current=login("thungan.gm001","Updated@456",200);
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+access(issued))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+access(current))).andExpect(status().isOk());
    }
    @Test void redisSessionFailureReturns503InsteadOfTreatingItAsExpiredCredentials() throws Exception {
        var issued=login();
        redis.opsForValue().set("auth:refresh:{7}","wrong-type-for-store-failure");
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+access(issued)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"));
        postJson("logout",Map.of("refreshToken",refresh(issued)),503,null);
    }
    @Test void correctlySignedJwtWithoutAnActiveSessionIsRejected() throws Exception {
        String token=applicationContext.getBean(vn.greenmart.identity.security.JwtTokens.class).issue(7,"CASHIER");
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void rotationKeepsSessionIdAndLookupKeysHaveBoundedLifetimeWithoutRawSecrets() throws Exception {
        var issued=login();
        var rotated=postJson("refresh",Map.of("refreshToken",refresh(issued)),200,null);
        var codec=applicationContext.getBean(vn.greenmart.identity.security.JwtTokens.class);
        assertThat(codec.decode(access(issued)).getClaimAsString("sid"))
                .isEqualTo(codec.decode(access(rotated)).getClaimAsString("sid"));
        assertThat(redis.keys("auth:refresh:{7}:lookup:*")).hasSize(2);
        for(String entry:redis.keys("auth:refresh:{7}:*")) {
            assertThat(redis.getExpire(entry)).isBetween(1L,604800L);
            assertThat(redis.opsForValue().get(entry)).doesNotContain(refresh(issued),refresh(rotated));
        }
    }
}
