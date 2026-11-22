-- WEB and MARKET use the same mock gateway/merchant; scope is server-owned, never supplied by a webhook.
ALTER TABLE sales_channel ADD COLUMN payment_provider_scope varchar(100) NOT NULL DEFAULT 'MOCK:GREENMART'
    CHECK (length(btrim(payment_provider_scope))>0);

CREATE TABLE channel_payment_reference (
    provider_scope varchar(100) NOT NULL,
    provider_ref varchar(60) NOT NULL CHECK (length(btrim(provider_ref))>0),
    order_id bigint NOT NULL UNIQUE REFERENCES sales_order(id),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(provider_scope,provider_ref)
);

-- Refuse ambiguous historical data; do not rename references, delete payments, or invent refunds.
DO $$ BEGIN
    IF EXISTS (
        SELECT c.payment_provider_scope,p.provider_ref
        FROM payment p JOIN sales_order o ON o.id=p.order_id JOIN sales_channel c ON c.id=o.channel_id
        WHERE c.code<>'POS' AND p.provider_ref IS NOT NULL AND p.status IN ('PAID','REFUNDED')
        GROUP BY c.payment_provider_scope,p.provider_ref HAVING count(DISTINCT p.order_id)>1
    ) OR EXISTS (
        SELECT p.order_id FROM payment p JOIN sales_order o ON o.id=p.order_id JOIN sales_channel c ON c.id=o.channel_id
        WHERE c.code<>'POS' AND p.provider_ref IS NOT NULL AND p.status IN ('PAID','REFUNDED')
        GROUP BY p.order_id HAVING count(DISTINCT p.provider_ref)>1
    ) THEN
        RAISE EXCEPTION 'Ambiguous historical online payment references: reconcile before applying V19. No payment was modified.';
    END IF;
END $$;

INSERT INTO channel_payment_reference(provider_scope,provider_ref,order_id)
SELECT DISTINCT c.payment_provider_scope,p.provider_ref,p.order_id
FROM payment p JOIN sales_order o ON o.id=p.order_id JOIN sales_channel c ON c.id=o.channel_id
WHERE c.code<>'POS' AND p.provider_ref IS NOT NULL AND p.status IN ('PAID','REFUNDED');

-- Keep the binding after refund and protect every database writer, not just the webhook service.
CREATE FUNCTION guard_channel_payment_reference() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE channel_code text; scope_value text;
BEGIN
    SELECT c.code,c.payment_provider_scope INTO channel_code,scope_value
    FROM sales_order o JOIN sales_channel c ON c.id=o.channel_id WHERE o.id=NEW.order_id;
    IF channel_code='POS' OR NEW.provider_ref IS NULL OR NEW.status NOT IN ('PAID','REFUNDED') THEN
        RETURN NEW;
    END IF;
    INSERT INTO channel_payment_reference(provider_scope,provider_ref,order_id)
    VALUES (scope_value,NEW.provider_ref,NEW.order_id) ON CONFLICT DO NOTHING;
    IF NOT EXISTS (SELECT 1 FROM channel_payment_reference
                   WHERE provider_scope=scope_value AND provider_ref=NEW.provider_ref AND order_id=NEW.order_id) THEN
        RAISE EXCEPTION USING ERRCODE='23505', MESSAGE='Online payment reference conflicts with an existing order binding';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER guard_channel_payment_reference
BEFORE INSERT OR UPDATE OF order_id,provider_ref,status ON payment
FOR EACH ROW EXECUTE FUNCTION guard_channel_payment_reference();

CREATE TABLE channel_event_receipt (
    channel_id bigint NOT NULL REFERENCES sales_channel(id),
    event_id uuid NOT NULL,
    order_id bigint NOT NULL REFERENCES sales_order(id),
    request_hash char(64) NOT NULL,
    response jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(channel_id,event_id)
);
COMMENT ON TABLE channel_event_receipt IS 'Retain at least 7 days; no automatic purge. Response is the first successful acknowledgement.';
COMMENT ON TABLE channel_payment_reference IS 'Permanent gateway/merchant reference-to-order binding; refunds never release it.';
