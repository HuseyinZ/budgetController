-- =====================================================================
--  V003 — Mutfak yazıcısı ŞABLON satırları (placeholder — gerçek IP YOK)
--
--  Üç mutfak kodu (DONER / FIRIN / OCAK) uygulamanın kategori→yazıcı rota
--  ekranında görünsün diye seed edilir. Ancak:
--    * host = 192.0.2.x  → RFC 5737 TEST-NET-1 (yönlendirilmez, dokümantasyon
--      aralığı). Gerçek bir cihaza asla ulaşmaz.
--    * is_active = 0     → KitchenRouter pasif yazıcıya YÖNLENDİRMEZ; operatör
--      gerçek IP'yi girip satırı aktifleştirene kadar mutfak baskısı
--      bilinçli olarak kapalıdır (sessiz yanlış hedef yerine açık eksiklik).
--
--  İlk kurulumda ZORUNLU adım (KURULUM_REHBERI §10):
--      UPDATE kitchen_printers SET host='<gerçek IP>', is_active=1 WHERE code='DONER';
--      ... (FIRIN, OCAK)
--
--  İdempotent: kod mevcutsa dokunmaz — üretimde gerçek satırların
--  üzerine YAZMAZ.
-- =====================================================================

INSERT INTO kitchen_printers (code, display_name, host, port, char_per_line, code_page, is_active, note)
SELECT * FROM (SELECT 'DONER' AS code, 'Döner' AS display_name,
                      '192.0.2.1' AS host, 9100 AS port, 42 AS char_per_line, 12 AS code_page,
                      0 AS is_active,
                      'KURULUM GEREKLİ: gerçek yazıcı IP girin ve is_active=1 yapın' AS note) AS d
WHERE NOT EXISTS (SELECT 1 FROM kitchen_printers WHERE code = 'DONER');

INSERT INTO kitchen_printers (code, display_name, host, port, char_per_line, code_page, is_active, note)
SELECT * FROM (SELECT 'FIRIN' AS code, 'Fırın' AS display_name,
                      '192.0.2.2' AS host, 9100 AS port, 42 AS char_per_line, 12 AS code_page,
                      0 AS is_active,
                      'KURULUM GEREKLİ: gerçek yazıcı IP girin ve is_active=1 yapın' AS note) AS d
WHERE NOT EXISTS (SELECT 1 FROM kitchen_printers WHERE code = 'FIRIN');

INSERT INTO kitchen_printers (code, display_name, host, port, char_per_line, code_page, is_active, note)
SELECT * FROM (SELECT 'OCAK' AS code, 'Ocak' AS display_name,
                      '192.0.2.3' AS host, 9100 AS port, 42 AS char_per_line, 12 AS code_page,
                      0 AS is_active,
                      'KURULUM GEREKLİ: gerçek yazıcı IP girin ve is_active=1 yapın' AS note) AS d
WHERE NOT EXISTS (SELECT 1 FROM kitchen_printers WHERE code = 'OCAK');

-- =====================================================================
--  V003 sonu
-- =====================================================================
