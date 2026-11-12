-- Run after a load test: every line must report 0.

-- Items that sold more than their quota.
SELECT 'oversold items' AS check, count(*) AS violations
FROM flash_sale_items WHERE sold > quota
UNION ALL
-- Orders per item must equal the item's sold counter.
SELECT 'sold counter != orders', count(*)
FROM flash_sale_items i
LEFT JOIN (SELECT item_id, count(*) AS n FROM flash_orders GROUP BY item_id) o ON o.item_id = i.id
WHERE i.sold <> COALESCE(o.n, 0)
UNION ALL
-- One flash sale product per user per day.
SELECT 'users with 2+ orders in a day', count(*)
FROM (SELECT user_id FROM flash_orders GROUP BY user_id, sale_date HAVING count(*) > 1) t
UNION ALL
-- Every order was paid exactly once from the wallet.
SELECT 'orders without exactly one payment', count(*)
FROM flash_orders o
WHERE (SELECT count(*) FROM wallet_transactions t
       WHERE t.type = 'FLASH_PURCHASE' AND t.ref_id = o.id) <> 1
UNION ALL
-- No negative balance or stock anywhere.
SELECT 'negative balances', count(*) FROM wallets WHERE balance < 0
UNION ALL
SELECT 'events not yet published', count(*) FROM outbox WHERE published_at IS NULL;
