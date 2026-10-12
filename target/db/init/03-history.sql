-- Historical order lines (1M rows, one every 10 s over ~115 days) so that the new_pricing aggregate is
-- genuinely expensive. Order ids are negative so they never clash with live orders.
INSERT INTO orders.order_lines (order_id, sku, qty, unit_price_cents, created_at)
SELECT -(g / 3),
       'SKU-' || lpad((1 + (g * 7919) % 50)::text, 3, '0'),
       1 + g % 4,
       199 + (g * 31) % 9800,
       now() - make_interval(secs => g * 10)
FROM generate_series(1::bigint, 1000000::bigint) AS g;
ANALYZE orders.order_lines;
