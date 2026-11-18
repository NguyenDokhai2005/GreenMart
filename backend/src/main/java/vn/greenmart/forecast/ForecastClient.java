package vn.greenmart.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class ForecastClient implements AutoCloseable {
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
    private final HttpClient transport;
    private final RestClient client;
    private final String token;
    public ForecastClient(@Value("${FORECAST_SERVICE_URL:http://forecast-service:8000}") URI base,
                          @Value("${FORECAST_INTERNAL_TOKEN:}") String token) {
        this.transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder().baseUrl(base.toString()).requestFactory(factory).build();
        this.token = token;
    }
    @jakarta.annotation.PreDestroy
    @Override public void close() { transport.close(); }

    public JsonNode start() { return call(true, "/forecast/run"); }
    public JsonNode get(long id) {
        if (id <= 0) throw new AppException(ErrorCode.VALIDATION_ERROR);
        return call(false, "/forecast/runs/" + id);
    }
    private JsonNode call(boolean start, String path) {
        if (token.isBlank()) throw new AppException(ErrorCode.FORECAST_UNAVAILABLE);
        try {
            var request = start ? client.post().uri(path) : client.get().uri(path);
            String payload = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(String.class);
            if (payload == null) throw new AppException(ErrorCode.FORECAST_UNAVAILABLE);
            return json.readTree(payload);
        } catch (RestClientResponseException ex) {
            // Never propagate upstream body, headers or exception messages containing credentials.
            if (ex.getStatusCode().value() == 404) throw new AppException(ErrorCode.NOT_FOUND);
            if (ex.getStatusCode().value() == 409) throw new AppException(ErrorCode.CONFLICT);
            throw new AppException(ErrorCode.FORECAST_UNAVAILABLE);
        } catch (org.springframework.web.client.RestClientException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new AppException(ErrorCode.FORECAST_UNAVAILABLE);
        }
    }
}
