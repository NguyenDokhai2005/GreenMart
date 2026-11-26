package vn.greenmart.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.forecast.service.ForecastRunQueryService;

@RestController
@RequestMapping("/forecasts/runs")
public class ForecastRunController {
    private final ForecastClient forecasts;
    private final JdbcTemplate jdbc;
    private final ForecastRunQueryService queries;
    public ForecastRunController(ForecastClient forecasts, JdbcTemplate jdbc,
                                ForecastRunQueryService queries) {
        this.forecasts = forecasts;
        this.jdbc = jdbc;
        this.queries = queries;
    }
    @GetMapping
    public PageResponse<ForecastRunQueryService.Run> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return queries.list(status, page, size);
    }
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode start(Authentication authentication) {
        long id = forecasts.start().path("runId").asLong();
        if (id <= 0) throw new AppException(ErrorCode.FORECAST_UNAVAILABLE);
        Long actor = authentication.getPrincipal() instanceof Long userId ? userId : null;
        jdbc.update("INSERT INTO audit_log(user_id,action,entity,entity_id) VALUES (?,'FORECAST_RUN','forecast_run',?)",
                actor, Long.toString(id));
        return get(id);
    }
    @GetMapping("/{runId}")
    public JsonNode get(@PathVariable long runId) {
        JsonNode result = forecasts.get(runId);
        if (!(result instanceof ObjectNode detail) || !detail.has("runId")) {
            throw new AppException(ErrorCode.FORECAST_UNAVAILABLE);
        }
        ObjectNode response = detail.deepCopy();
        response.set("id", response.remove("runId"));
        return response;
    }
}
