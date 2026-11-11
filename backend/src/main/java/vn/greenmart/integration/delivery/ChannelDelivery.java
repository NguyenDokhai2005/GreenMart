package vn.greenmart.integration.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;
import vn.greenmart.common.logging.TraceContext;
import vn.greenmart.integration.channel.ChannelSignatures;
import vn.greenmart.integration.outbox.OutboxService;

@Configuration
public class ChannelDelivery {
    private static final String QUEUE = "greenmart.channel.delivery";
    private static final String STOCK = "channel.stock", STATUS = "channel.order.status";
    private static final int[] DELAYS = {1, 2, 4, 8, 16};
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ChannelSignatures signatures;
    private final OutboxService outbox;
    private final URI base;
    private final boolean workerEnabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public ChannelDelivery(JdbcTemplate jdbc, ObjectMapper json, ChannelSignatures signatures, OutboxService outbox,
                           @Value("${MOCK_CONNECTOR_URL:http://mock-connector:8090}") URI base,
                           @Value("${greenmart.outbox.enabled:true}") boolean workerEnabled) {
        this.jdbc = jdbc;
        this.json = json;
        this.signatures = signatures;
        this.outbox = outbox;
        this.base = base;
        this.workerEnabled = workerEnabled;
    }

    @Bean
    Declarables deliveryTopology() {
        var queue = QueueBuilder.durable(QUEUE).deadLetterExchange("greenmart.dlx")
                .deadLetterRoutingKey("integration.failed").build();
        return new Declarables(queue,
                new Binding(QUEUE, Binding.DestinationType.QUEUE, "greenmart.events", STOCK, Map.of()),
                new Binding(QUEUE, Binding.DestinationType.QUEUE, "greenmart.events", STATUS, Map.of()),
                new Binding("greenmart.integration.dlq", Binding.DestinationType.QUEUE,
                        "greenmart.events", "channel.delivery.failed", Map.of()));
    }

    @RabbitListener(queues = QUEUE, autoStartup = "${greenmart.outbox.enabled:true}")
    @Transactional
    public void accept(Message message) throws IOException {
        var props = message.getMessageProperties();
        String eventId = props.getMessageId(), routing = props.getReceivedRoutingKey();
        var envelope = json.readTree(message.getBody());
        String channel = envelope.path("channelCode").asText();
        String externalId = envelope.path("externalId").asText(null);
        if (eventId == null || !("WEB".equals(channel) || "MARKET".equals(channel))
                || !envelope.path("body").isObject()
                || !(STOCK.equals(routing) || (STATUS.equals(routing) && externalId != null
                    && !externalId.isBlank() && externalId.length() <= 60))) {
            throw new AmqpRejectAndDontRequeueException("Invalid channel callback event");
        }
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "channel-delivery:" + eventId);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM channel_delivery WHERE event_id=?)",
                Boolean.class, eventId))) return;
        long log = jdbc.queryForObject("""
                INSERT INTO sync_log(channel_id,direction,external_id,status)
                VALUES ((SELECT id FROM sales_channel WHERE code=?),'OUT',?,'RETRYING') RETURNING id
                """, Long.class, channel, externalId);
        jdbc.update("""
                INSERT INTO channel_delivery(event_id,sync_log_id,routing_key,channel_code,external_id,body,trace_id)
                VALUES (?,?,?,?,?,?::jsonb,?)
                """, eventId, log, routing, channel, externalId, envelope.path("body").toString(), TraceContext.current());
    }

    record Job(String event, long log, String routing, String channel, String externalId,
               String body, String trace, int attempts) {}

    @Scheduled(fixedDelayString = "${greenmart.channel.delivery.poll-ms:250}", scheduler = "channelDeliveryScheduler")
    @Transactional
    public void poll() {
        if (workerEnabled) deliverOne();
    }

    @Bean
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler channelDeliveryScheduler() {
        return scheduler("channel-delivery-");
    }

    @Bean
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler() {
        return scheduler("greenmart-scheduled-");
    }

    private org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler(String name) {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix(name);
        return scheduler;
    }

    @Transactional
    public void deliverOne() {
        var jobs = jdbc.query("""
                SELECT d.event_id,d.sync_log_id,d.routing_key,d.channel_code,d.external_id,d.body,d.trace_id,l.attempts
                FROM channel_delivery d JOIN sync_log l ON l.id=d.sync_log_id
                WHERE d.next_attempt_at <= clock_timestamp()
                ORDER BY d.next_attempt_at,d.event_id LIMIT 1 FOR UPDATE OF d SKIP LOCKED
                """, (rs,row) -> new Job(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),
                rs.getString(5),rs.getString(6),rs.getString(7),rs.getInt(8)));
        if (jobs.isEmpty()) return;
        var job = jobs.getFirst();
        String previousTrace = MDC.get("traceId");
        MDC.put("traceId", job.trace());
        try {
            int attempt = job.attempts() + 1;
            String error;
            boolean permanent = false;
            try {
                int status = send(job);
                if (status == 204) {
                    record(job, attempt, "SUCCESS", null, null);
                    return;
                }
                error = "HTTP " + status;
                permanent = status >= 400 && status < 500 && status != 408 && status != 429;
            } catch (IOException ex) {
                error = "Kết nối callback thất bại";
            } catch (IllegalStateException ex) {
                error = "Thiếu cấu hình ký callback";
                permanent = true;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!permanent && attempt <= DELAYS.length) {
                record(job, attempt, "RETRYING", error, DELAYS[attempt - 1]);
            } else {
                record(job, attempt, "FAILED", error, null);
                outbox.enqueue("channel-delivery", job.event(), "channel.delivery.failed",
                        Map.of("eventId", job.event(), "channelCode", job.channel(), "routingKey", job.routing(),
                               "attempts", attempt, "error", error));
            }
        } finally {
            if (previousTrace == null) MDC.remove("traceId"); else MDC.put("traceId", previousTrace);
        }
    }

    private void record(Job job, int attempts, String status, String error, Integer delay) {
        jdbc.update("UPDATE sync_log SET attempts=?,status=?,error=? WHERE id=?", attempts, status, error, job.log());
        jdbc.update("UPDATE channel_delivery SET next_attempt_at=clock_timestamp()+make_interval(secs => ?::int) WHERE event_id=?",
                delay, job.event());
    }

    private int send(Job job) throws IOException, InterruptedException {
        var url = UriComponentsBuilder.fromUri(base).pathSegment("mock", job.channel());
        if (STOCK.equals(job.routing())) url.pathSegment("stock");
        else url.pathSegment("orders", "{externalId}", "status");
        URI endpoint = url.encode().buildAndExpand(STOCK.equals(job.routing()) ? Map.of()
                : Map.of("externalId", job.externalId())).toUri();
        byte[] body = job.body().getBytes(StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").header("X-Signature", signatures.sign(job.channel(), body))
                .header(TraceContext.HEADER, job.trace()).PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
