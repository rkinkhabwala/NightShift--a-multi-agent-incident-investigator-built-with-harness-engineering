-- Reference data: 1000 customers (35 % without a loyalty tier), 50 SKUs plus one that is
-- permanently out of stock (SKU-OOS, used by the load generator for background 409s).

INSERT INTO orders.customers (id, name, loyalty_tier)
SELECT i,
       'customer-' || i,
       CASE WHEN i % 20 < 7 THEN NULL ELSE (ARRAY ['BRONZE', 'SILVER', 'GOLD'])[1 + i % 3] END
FROM generate_series(1, 1000) AS i;

INSERT INTO orders.price_list (sku, name, price_cents)
SELECT 'SKU-' || lpad(i::text, 3, '0'), 'product ' || i, 199 + (i * 197) % 9800
FROM generate_series(1, 50) AS i;
INSERT INTO orders.price_list (sku, name, price_cents) VALUES ('SKU-OOS', 'discontinued product', 1500);

INSERT INTO inventory.stock (sku, available)
SELECT 'SKU-' || lpad(i::text, 3, '0'), 100000 FROM generate_series(1, 50) AS i;
INSERT INTO inventory.stock (sku, available) VALUES ('SKU-OOS', 0);
