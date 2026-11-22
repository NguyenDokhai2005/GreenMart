-- #430. V19 from #416/PR #477 is already integrated; apply V19 before V20.
-- Transactional, forward-only. Never silently rename external identifiers or merge categories.
LOCK TABLE category, channel_product IN SHARE ROW EXCLUSIVE MODE;

-- Unicode White_Space matches Java (?U)\s. Case identity is compared in PostgreSQL only.
CREATE FUNCTION greenmart_category_key(value text) RETURNS text
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT lower(btrim(regexp_replace(normalize(value, NFC),
        U&'[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]+', ' ', 'g')))
$$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM category WHERE greenmart_category_key(name)='') THEN
        RAISE EXCEPTION 'Issue 430: blank normalized category names; reconcile legacy data before migration';
    END IF;
    IF EXISTS (SELECT 1 FROM category GROUP BY parent_id,greenmart_category_key(name) HAVING count(*)>1) THEN
        RAISE EXCEPTION 'Issue 430: duplicate normalized sibling category names; reconcile legacy data before migration';
    END IF;
    IF EXISTS (SELECT 1 FROM channel_product WHERE external_sku !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,59}$') THEN
        RAISE EXCEPTION 'Issue 430: noncanonical external SKUs; reconcile with provider before migration';
    END IF;
END $$;

-- PostgreSQL 15+: NULLS NOT DISTINCT also arbitrates concurrent root-name inserts.
CREATE UNIQUE INDEX uq_category_parent_name_key
    ON category(parent_id,greenmart_category_key(name)) NULLS NOT DISTINCT;
ALTER TABLE category ADD CONSTRAINT ck_category_nonblank_name CHECK (greenmart_category_key(name)<>'');
ALTER TABLE channel_product ADD CONSTRAINT ck_channel_product_external_sku
    CHECK (external_sku ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,59}$');
