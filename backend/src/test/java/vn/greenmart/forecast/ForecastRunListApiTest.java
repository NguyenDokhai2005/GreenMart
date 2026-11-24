package vn.greenmart.forecast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.PostgresTestImage;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class ForecastRunListApiTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ForecastClient forecasts;

    @Test @WithMockUser(roles="ADMIN")
    void emptyPageDoesNotDependOnUpstreamAvailability() throws Exception {
        mvc.perform(get("/api/v1/forecasts/runs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty()).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
        verifyNoInteractions(forecasts);
    }

    @Test @WithMockUser(roles="OWNER")
    void newestFirstWithStablePaginationAndContractFields() throws Exception {
        fixtures();
        mvc.perform(get("/api/v1/forecasts/runs?size=2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(953))
                .andExpect(jsonPath("$.content[1].id").value(952))
                .andExpect(jsonPath("$.content[0].finishedAt").value(nullValue()))
                .andExpect(jsonPath("$.content[0].mae").value(nullValue()))
                .andExpect(jsonPath("$.content[0].baselineWape").value(nullValue()))
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/v1/forecasts/runs?size=2&page=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(951)).andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get("/api/v1/forecasts/runs?status=DONE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(951))
                .andExpect(jsonPath("$.content[0].mae").value(1.25))
                .andExpect(jsonPath("$.totalElements").value(1));
        verifyNoInteractions(forecasts);
    }

    @ParameterizedTest @ValueSource(strings={"page=-1","size=0","size=101","status=INVALID","page=abc"})
    @WithMockUser(roles="ADMIN")
    void invalidParametersReturn400(String query) throws Exception {
        mvc.perform(get("/api/v1/forecasts/runs?"+query)).andExpect(status().isBadRequest());
    }

    @Test void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/v1/forecasts/runs")).andExpect(status().isUnauthorized());
    }
    @Test @WithMockUser(roles="CASHIER") void cashierIsRejected() throws Exception {
        mvc.perform(get("/api/v1/forecasts/runs")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="BRANCH_MANAGER") void branchManagerIsRejected() throws Exception {
        mvc.perform(get("/api/v1/forecasts/runs")).andExpect(status().isForbidden());
    }

    private void fixtures() {
        jdbc.update("""
                INSERT INTO forecast_run(id,status,started_at,finished_at,model_version,mae,wape,baseline_mae,baseline_wape)
                VALUES (951,'DONE','2026-11-20T00:00:00Z','2026-11-20T00:01:00Z','fixture',1.25,2,3,4),
                       (952,'FAILED','2026-11-21T00:00:00Z',NULL,'fixture',NULL,NULL,NULL,NULL),
                       (953,'RUNNING','2026-11-21T00:00:00Z',NULL,'fixture',NULL,NULL,NULL,NULL)
                """);
    }
}
