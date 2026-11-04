package vn.greenmart.integration.outbox;

public interface EventPublisher {
    void publish(long eventId, String eventType, String payload, String traceId) throws Exception;
}
