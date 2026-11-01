-- BR-06: corrections require a new adjustment entry, never rewriting history.
CREATE FUNCTION reject_inventory_transaction_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'inventory_transaction is append-only (BR-06)'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER inventory_transaction_append_only
BEFORE UPDATE OR DELETE ON inventory_transaction
FOR EACH ROW EXECUTE FUNCTION reject_inventory_transaction_mutation();
