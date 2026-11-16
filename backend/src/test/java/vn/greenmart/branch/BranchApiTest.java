package vn.greenmart.branch;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.PostgresTestImage;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
@Transactional
class BranchApiTest {
    private static final String PATH = "/api/v1/branches";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.forecast_user", () -> "forecast_ro");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void fixtures() {
        jdbc.update("""
                INSERT INTO branch(id, code, name, address, is_active) VALUES
                (71, 'GM-071', 'Branch 71', '71 Green Street', true),
                (72, 'GM-072', 'Branch 72', NULL, false),
                (73, 'GM-073', 'Branch 73', NULL, true)
                """);
        jdbc.update("""
                INSERT INTO app_user(id, username, password_hash, full_name, role) VALUES
                (700, 'branch-test', 'hash', 'Branch Test', 'CASHIER'),
                (701, 'no-branch-test', 'hash', 'No Branch Test', 'CASHIER')
                """);
        jdbc.update("INSERT INTO user_branch(user_id, branch_id) VALUES (700, 71), (700, 72)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "OWNER"})
    void returnsAllBranchesIncludingInactiveForPrivilegedRoles(String role) throws Exception {
        mvc.perform(get(PATH).with(user("700").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(71, 72, 73)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CASHIER", "STOREKEEPER", "BRANCH_MANAGER"})
    void returnsAllActiveBranchesWithContractFields(String role) throws Exception {
        mvc.perform(get(PATH).with(user("700").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(71, 73)))
                .andExpect(jsonPath("$[0].code").value("GM-071"))
                .andExpect(jsonPath("$[0].name").value("Branch 71"))
                .andExpect(jsonPath("$[0].address").value("71 Green Street"))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[1].address").value(nullValue()))
                .andExpect(jsonPath("$[1].active").value(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CASHIER", "STOREKEEPER", "BRANCH_MANAGER"})
    void honorsActiveTrueAndHidesInactiveBranches(String role) throws Exception {
        mvc.perform(get(PATH).with(user("700").roles(role)).param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(71, 73)));
        mvc.perform(get(PATH).with(user("700").roles(role)).param("active", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "OWNER"})
    void activeFilterAlsoAppliesToPrivilegedRoles(String role) throws Exception {
        mvc.perform(get(PATH).with(user("700").roles(role)).param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(71, 73)));
        mvc.perform(get(PATH).with(user("700").roles(role)).param("active", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(72)))
                .andExpect(jsonPath("$[0].active").value(false));
    }

    @Test
    void returnsActiveBranchesWithoutBranchAssignments() throws Exception {
        mvc.perform(get(PATH).with(user("701").roles("CASHIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(71, 73)));
    }

    @Test
    void rejectsInvalidActiveFilter() throws Exception {
        mvc.perform(get(PATH).with(user("700").roles("CASHIER")).param("active", "invalid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }
}
