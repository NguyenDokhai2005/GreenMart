CREATE TABLE channel_delivery (
    event_id VARCHAR(60) PRIMARY KEY,
    sync_log_id BIGINT NOT NULL UNIQUE REFERENCES sync_log(id),
    routing_key VARCHAR(40) NOT NULL,
    channel_code VARCHAR(10) NOT NULL,
    external_id VARCHAR(60),
    body JSONB NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    next_attempt_at TIMESTAMPTZ DEFAULT clock_timestamp()
);
CREATE INDEX channel_delivery_due ON channel_delivery(next_attempt_at) WHERE next_attempt_at IS NOT NULL;
