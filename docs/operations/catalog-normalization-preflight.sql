-- #430: read-only checks BEFORE V20. Run on a private copy with administrative access.
BEGIN TRANSACTION READ ONLY;

WITH names AS (
    SELECT id,parent_id,lower(btrim(regexp_replace(normalize(name, NFC),
        U&'[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]+', ' ', 'g'))) AS name_key
    FROM category
)
SELECT parent_id,name_key,array_agg(id ORDER BY id) AS category_ids
FROM names GROUP BY parent_id,name_key HAVING count(*)>1;

SELECT id AS blank_category_id FROM category
WHERE btrim(regexp_replace(normalize(name, NFC),
    U&'[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]+', ' ', 'g'))='';

SELECT channel_id,product_id,external_sku FROM channel_product
WHERE external_sku !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,59}$';

COMMIT;
