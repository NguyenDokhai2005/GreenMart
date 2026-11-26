ALTER TABLE channel_delivery ADD COLUMN first_failed_at TIMESTAMPTZ;
ALTER TABLE channel_delivery ADD COLUMN last_recovery_warning_at TIMESTAMPTZ;

UPDATE channel_delivery d SET first_failed_at=l.created_at
FROM sync_log l WHERE l.id=d.sync_log_id AND l.status='FAILED';

CREATE TABLE channel_recovery (
    channel_code VARCHAR(10) PRIMARY KEY REFERENCES sales_channel(code),
    next_probe_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO channel_recovery(channel_code) VALUES ('WEB'),('MARKET');
CREATE INDEX idx_channel_delivery_stream ON channel_delivery(channel_code,routing_key,external_id,sync_log_id);

CREATE TABLE channel_stock_snapshot (
    channel_code VARCHAR(10) NOT NULL REFERENCES sales_channel(code),
    external_sku VARCHAR(60) NOT NULL,
    version BIGINT NOT NULL,
    item JSONB NOT NULL,
    PRIMARY KEY(channel_code,external_sku)
);
INSERT INTO channel_stock_snapshot(channel_code,external_sku,version,item)
SELECT DISTINCT ON (d.channel_code,item->>'externalSku')
       d.channel_code,item->>'externalSku',(item->>'version')::bigint,item
FROM channel_delivery d CROSS JOIN LATERAL jsonb_array_elements(d.body->'items') item
WHERE d.routing_key='channel.stock'
ORDER BY d.channel_code,item->>'externalSku',(item->>'version')::bigint DESC,d.sync_log_id DESC;
