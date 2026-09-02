-- =====================================================================
--  [LEGACY ARŞİV — ÇALIŞTIRMAYIN] Canonical karşılığı: V001
--  (products/order_items.pieces_per_portion+unit_label, expenses kg alanları).
-- =====================================================================
--  Tarih   : 2026-05-17 (B)
--  Amaç    : (1) Şiş bazlı fiyatlandırma:
--                  products.pieces_per_portion (porsiyondaki şiş sayısı)
--                  products.unit_label         ('şiş', 'porsiyon', 'kg' …)
--              order_items'a snapshot olarak aynı iki alan eklenir.
--           (2) Kg bazlı gider girişi:
--                  expenses.quantity_kg        (kilo)
--                  expenses.unit_price_per_kg  (kg fiyatı)
-- =====================================================================

ALTER TABLE products
  ADD COLUMN pieces_per_portion INT NULL
       COMMENT '1 porsiyonda kaç birim (şiş) var? NULL → porsiyon bazlı',
  ADD COLUMN unit_label VARCHAR(16) NULL
       COMMENT 'Birim etiketi: porsiyon / şiş / kg / adet';

ALTER TABLE order_items
  ADD COLUMN pieces_per_portion INT NULL,
  ADD COLUMN unit_label VARCHAR(16) NULL;

ALTER TABLE expenses
  ADD COLUMN quantity_kg DECIMAL(10,3) NULL
       COMMENT 'Kg-bazlı giriyse miktar (kg)',
  ADD COLUMN unit_price_per_kg DECIMAL(10,2) NULL
       COMMENT 'Kg-bazlı giriyse 1 kg fiyatı (TL)';
