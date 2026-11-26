-- Keep V22 unchanged: QA may already have applied that migration.
ALTER TABLE channel_delivery
    ADD COLUMN recovery_count INTEGER NOT NULL DEFAULT 0 CHECK (recovery_count BETWEEN 0 AND 3),
    ADD COLUMN cycle_attempts INTEGER NOT NULL DEFAULT 0 CHECK (cycle_attempts BETWEEN 0 AND 6);

UPDATE channel_delivery d SET cycle_attempts=LEAST(l.attempts,6)
FROM sync_log l WHERE l.id=d.sync_log_id;

-- Fences delayed probes: a response from an expired claim cannot drain another batch.
ALTER TABLE channel_recovery ADD COLUMN probe_token UUID;
