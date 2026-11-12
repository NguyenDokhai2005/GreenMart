-- Only in a newly seeded QA150 DB. No UPSERT/reset of existing inventory.
BEGIN;
INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price,is_active)
SELECT 900150,'QA150-STOCK5','QA150 stock race','piece',min(id),10000,5000,true FROM category;
INSERT INTO inventory(branch_id,product_id,on_hand,reserved) VALUES(1,900150,5,0);
COMMIT;
