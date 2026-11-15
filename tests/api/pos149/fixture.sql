-- Only load in the NEW, isolated greenmart-qa149 database, after the SV06 seed.
-- No promotion: 180000 + 2*29000 = 238000, cash 250000 -> change 12000.
BEGIN;
INSERT INTO product (id,sku,name,unit,category_id,sale_price,cost_price,is_active)
SELECT 900001,'QA149-RICE','QA149 Rice','bag',min(id),180000,100000,true FROM category;
INSERT INTO product (id,sku,name,unit,category_id,sale_price,cost_price,is_active)
SELECT 900002,'QA149-MILK','QA149 Milk','box',min(id),29000,20000,true FROM category;
INSERT INTO inventory(branch_id,product_id,on_hand,reserved) VALUES (1,900001,10,0),(1,900002,20,0);
COMMIT;
