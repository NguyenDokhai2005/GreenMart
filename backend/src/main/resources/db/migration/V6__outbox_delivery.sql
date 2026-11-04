-- Additive migration; coordinate the V6 version with SV03 before merging parallel migrations.
ALTER TABLE outbox_event ADD COLUMN trace_id VARCHAR(64);
ALTER TABLE outbox_event ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX idx_outbox_pending_delivery ON outbox_event(next_attempt_at, id) WHERE status = 'PENDING';
