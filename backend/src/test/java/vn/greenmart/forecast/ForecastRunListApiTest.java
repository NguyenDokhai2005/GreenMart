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

    @Test @WithMockUser(roles="OWNER")
    void standardizedRunExposesMetadataWhileLegacyAndNonDoneStayNullSafe() throws Exception {
        fixtures();
        jdbc.update("""
                INSERT INTO forecast_run(
                    id,status,started_at,finished_at,model_version,mae,wape,baseline_mae,baseline_wape,
                    metric_horizon,aggregation,postprocess_version,metric_protocol_version,
                    cutoff_date,evaluation_cutoff_date,test_start_date,test_end_date,
                    stockout_mode,baseline_name,data_source,data_source_hash,metrics_breakdown
                )
                VALUES (
                    954,'DONE','2026-11-25T00:00:00Z','2026-11-25T00:02:00Z','LightGBM-Tweedie-H7-H30',
                    0.8757,0.7913,1.0706,0.9674,
                    7,'HORIZON_AGGREGATE_POSTPROC','slow-th-h7_0.4-h30_0.4-sh28-v1','ac05-v2-h7-h30-postproc-20261125',
                    '2026-10-31','2026-10-01','2026-10-02','2026-10-31',
                    'EXCLUDED_CLEAN','rolling_mean_28','postgresql.daily_sales','abc123','{"schemaVersion":"2.0"}'::jsonb
                )
                """);
        mvc.perform(get("/api/v1/forecasts/runs?size=10")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(954))
                .andExpect(jsonPath("$.content[0].legacy").value(false))
                .andExpect(jsonPath("$.content[0].metricHorizon").value(7))
                .andExpect(jsonPath("$.content[0].aggregation").value("HORIZON_AGGREGATE_POSTPROC"))
                .andExpect(jsonPath("$.content[0].postprocessVersion").value("slow-th-h7_0.4-h30_0.4-sh28-v1"))
                .andExpect(jsonPath("$.content[0].cutoffDate").value("2026-10-31"))
                .andExpect(jsonPath("$.content[0].evaluationCutoffDate").value("2026-10-01"))
                .andExpect(jsonPath("$.content[0].baselineName").value("rolling_mean_28"))
                .andExpect(jsonPath("$.content[0].metricsBreakdown").doesNotExist())
                .andExpect(jsonPath("$.content[1].id").value(953))
                .andExpect(jsonPath("$.content[1].legacy").value(false))
                .andExpect(jsonPath("$.content[1].metricHorizon").value(nullValue()))
                .andExpect(jsonPath("$.content[3].id").value(951))
                .andExpect(jsonPath("$.content[3].legacy").value(true))
                .andExpect(jsonPath("$.content[3].aggregation").value(nullValue()));
        verifyNoInteractions(forecasts);
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
