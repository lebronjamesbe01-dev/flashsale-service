-- Demo catalog and flash sale calendar, loaded only with the "demo" profile.
-- Slots are relative to the moment this migration runs: every 2 hours, for 7 days starting today
-- (Asia/Ho_Chi_Minh), so a slot is always open when the stack is tried out.
-- Each slot sells 3 of the 5 products: one scarce item (quota 5) to see SOLD_OUT quickly,
-- and two larger ones (quota 50 and 100).

INSERT INTO products (id, sku, name, list_price, status, created_at, updated_at) VALUES
    ('00000000-0000-0000-0002-000000000001', 'IPHONE-17-128',  'iPhone 17 128GB',       24990000, 'ACTIVE', now(), now()),
    ('00000000-0000-0000-0002-000000000002', 'AIRPODS-PRO-3',  'AirPods Pro 3',          6490000, 'ACTIVE', now(), now()),
    ('00000000-0000-0000-0002-000000000003', 'GALAXY-S26',     'Samsung Galaxy S26',    22990000, 'ACTIVE', now(), now()),
    ('00000000-0000-0000-0002-000000000004', 'MI-BAND-10',     'Xiaomi Smart Band 10',    990000, 'ACTIVE', now(), now()),
    ('00000000-0000-0000-0002-000000000005', 'KINDLE-PW-6',    'Kindle Paperwhite 6',    3590000, 'ACTIVE', now(), now())
ON CONFLICT (sku) DO NOTHING;

INSERT INTO flash_sale_slots (id, name, start_at, end_at, status, created_at, updated_at)
SELECT md5('demo-slot-' || d || '-' || h)::uuid,
       'Flash sale ' || lpad(h::text, 2, '0') || ':00-' || lpad((h + 2)::text, 2, '0') || ':00',
       slot_start,
       slot_start + interval '2 hours',
       'PUBLISHED', now(), now()
FROM generate_series(0, 6) AS d,
     generate_series(0, 22, 2) AS h,
     LATERAL (SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Ho_Chi_Minh')
                      + make_interval(days => d, hours => h)) AT TIME ZONE 'Asia/Ho_Chi_Minh' AS slot_start) t
ON CONFLICT (id) DO NOTHING;

INSERT INTO flash_sale_items (id, slot_id, product_id, sale_price, quota, sold, created_at, updated_at)
SELECT md5('demo-item-' || s.id || '-' || k)::uuid,
       s.id,
       p.id,
       round(p.list_price * (0.5 + 0.1 * k), -3),
       (ARRAY[5, 50, 100])[k + 1],
       0, now(), now()
FROM flash_sale_slots s
CROSS JOIN generate_series(0, 2) AS k
JOIN (SELECT id, list_price, (row_number() OVER (ORDER BY id) - 1)::int AS idx
      FROM products
      WHERE id::text LIKE '00000000-0000-0000-0002-%') p
  ON p.idx = ((extract(hour FROM s.start_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::int / 2 + k) % 5)
WHERE s.id = md5('demo-slot-' || ((s.start_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
                                  - (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)
                 || '-' || extract(hour FROM s.start_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::int)::uuid
ON CONFLICT (id) DO NOTHING;

-- Warehouse stock: everything the quotas reserve, plus 1000 free units per product.
INSERT INTO inventory (product_id, on_hand, reserved, updated_at)
SELECT p.id, COALESCE(r.reserved, 0) + 1000, COALESCE(r.reserved, 0), now()
FROM products p
LEFT JOIN (SELECT product_id, SUM(quota)::int AS reserved
           FROM flash_sale_items GROUP BY product_id) r ON r.product_id = p.id
WHERE p.id::text LIKE '00000000-0000-0000-0002-%'
ON CONFLICT (product_id) DO NOTHING;

-- Demo users already hold balance (the assignment assumes it): 100,000,000 VND each.
INSERT INTO wallets (user_id, balance, updated_at)
SELECT id, 100000000, now()
FROM users
WHERE identifier LIKE '%@flashsale.local'
ON CONFLICT (user_id) DO NOTHING;
