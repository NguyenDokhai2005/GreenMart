package vn.greenmart.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;

@RestController
@RequestMapping("/forecasts/runs")
public class ForecastRunController {
    private final ForecastClient forecasts;
    private final JdbcTemplate jdbc;
    public ForecastRunController(ForecastClient forecasts, JdbcTemplate jdbc) {
        this.forecasts = forecasts;
        this.jdbc = jdbc;
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
