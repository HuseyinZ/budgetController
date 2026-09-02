-- =====================================================================
--  V002 — Zorunlu referans verisi: roller
--
--  model.Role enum'u ile birebir: ADMIN | KASIYER | GARSON.
--  users.role_id → roles.id; UserJdbcDAO rolü ada göre çözer
--  ("SELECT id FROM roles WHERE name=?"), bu yüzden adlar sabittir.
--
--  İdempotent: satır varsa dokunmaz (INSERT ... WHERE NOT EXISTS).
--  Kullanıcı satırı İÇERMEZ — ilk admin, tools CLI ile kurulumda oluşturulur.
-- =====================================================================

INSERT INTO roles (name)
SELECT * FROM (SELECT 'ADMIN' AS name) AS d
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = 'ADMIN');

INSERT INTO roles (name)
SELECT * FROM (SELECT 'KASIYER' AS name) AS d
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = 'KASIYER');

INSERT INTO roles (name)
SELECT * FROM (SELECT 'GARSON' AS name) AS d
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = 'GARSON');

-- =====================================================================
--  V002 sonu
-- =====================================================================
