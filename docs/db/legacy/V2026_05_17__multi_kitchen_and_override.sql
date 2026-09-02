-- =====================================================================
--  [LEGACY ARŞİV — ÇALIŞTIRMAYIN] Sanitize edildi: IP RFC 5737 (192.0.2.x).
--  Canonical karşılığı: V001 (kitchen_override_id). Yeniden adlandırma ve
--  kullanıcı pasifleştirme tek seferlik veri işlemleriydi; baseline'a girmez.
-- =====================================================================
--  Tarih   : 2026-05-17
--  Amaç    : (1) Mutfak yazıcılarını "Döner / Fırın / Ocak" olarak
--               yeniden adlandır.
--            (2) order_items'a kitchen_override_id ekle (garson satır
--               bazlı mutfak değiştirebilsin).
--            (3) Eski güvensiz plaintext parolaları temizle.
-- =====================================================================

-- 1) Mutfak yazıcılarını yeni adlarla güncelle / oluştur
UPDATE kitchen_printers
   SET code='DONER', display_name='Döner',
       note='Döner / Dürüm / İskender'
 WHERE code='KITCHEN_1';

UPDATE kitchen_printers
   SET code='FIRIN', display_name='Fırın',
       note='Pide / Lahmacun / Fırın yemekleri'
 WHERE code='KITCHEN_2';

INSERT INTO kitchen_printers (code, display_name, host, port, char_per_line, note)
SELECT * FROM (SELECT 'OCAK' AS code, 'Ocak' AS display_name,
                      '192.0.2.3' AS host, 9100 AS port, 42 AS char_per_line,
                      'Ciğer / Şiş / Izgara' AS note) AS d
WHERE NOT EXISTS (SELECT 1 FROM kitchen_printers WHERE code='OCAK');

-- 2) order_items.kitchen_override_id
ALTER TABLE order_items
  ADD COLUMN kitchen_override_id INT NULL,
  ADD CONSTRAINT fk_oi_kitchen_override
      FOREIGN KEY (kitchen_override_id) REFERENCES kitchen_printers(id)
      ON DELETE SET NULL;

-- 3) Eski plaintext parolaları temizle (güvenlik) — veri SİLİNMEZ, giriş engellenir
UPDATE users
   SET is_active = 0
 WHERE password_hash IS NULL
    OR password_hash NOT LIKE '$2%';
