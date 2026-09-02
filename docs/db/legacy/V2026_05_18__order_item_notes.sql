-- =====================================================================
--  [LEGACY ARŞİV — ÇALIŞTIRMAYIN] Canonical karşılığı: V001 (order_items.note).
-- =====================================================================
--  Tarih   : 2026-05-18
--  Amaç    : Sipariş kalemleri için "not" alanı.
--            Garson belirli bir kalemi özelleştirebilsin:
--            örn. "Az pişmiş", "Soğansız, tuzsuz", "Bibersiz, acılı"
-- =====================================================================

ALTER TABLE order_items
  ADD COLUMN note VARCHAR(255) NULL
       COMMENT 'Bu satıra ait özel not (örn. "soğansız, az pişmiş")';
