-- =====================================================================
--  V004 — DB tabanlı masa düzeni temeli + ürün birimleri kaynağı
--
--  A) restaurant_areas / restaurant_table_layout
--     Fiziksel (yapılandırılmış) masa düzeni artık DB'de tutulur.
--     dining_tables RUNTIME durumu tutmaya devam eder; bu migration ona
--     dokunmaz, FK eklemez ve davranışını değiştirmez.
--     Seed: classpath restaurant-layout.properties'teki 13 alan / 70 masa.
--
--  B) product_units
--     Birim listesi koddan çıkarılıp DB'ye taşınır. products.unit_label
--     VARCHAR olarak KALIR; FK eklenmez, mevcut ürün verisi değiştirilmez.
--     Seed: porsiyon, şiş, adet, kg, tabak, kase + products tablosunda
--     bulunan özel (listede olmayan) unit_label değerleri.
--
--  İdempotent: tüm seed'ler NOT EXISTS korumalıdır; tekrar çalıştırmak
--  mevcut satırların üzerine YAZMAZ.
-- =====================================================================

-- ---------------------------------------------------------------------
--  A.1 — Alanlar (bina / kat / salon)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS restaurant_areas (
    id             INT          NOT NULL AUTO_INCREMENT,
    building       VARCHAR(64)  NOT NULL,
    floor          VARCHAR(64)  NOT NULL,
    salon          VARCHAR(64)  NOT NULL DEFAULT '',
    display_order  INT          NOT NULL DEFAULT 0,
    is_active      TINYINT(1)   NOT NULL DEFAULT 1,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_areas_building_floor_salon (building, floor, salon)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- ---------------------------------------------------------------------
--  A.2 — Masa yerleşimi (koordinatlar ileride 0-1000 normalize edilecek)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS restaurant_table_layout (
    table_no       INT          NOT NULL,
    area_id        INT          NOT NULL,
    pos_x          SMALLINT     NULL,
    pos_y          SMALLINT     NULL,
    width          SMALLINT     NULL,
    height         SMALLINT     NULL,
    shape          VARCHAR(16)  NOT NULL DEFAULT 'RECT',
    rotation_deg   SMALLINT     NOT NULL DEFAULT 0,
    display_order  INT          NOT NULL DEFAULT 0,
    is_active      TINYINT(1)   NOT NULL DEFAULT 1,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (table_no),
    KEY idx_table_layout_area (area_id),
    CONSTRAINT fk_table_layout_area FOREIGN KEY (area_id)
        REFERENCES restaurant_areas (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- ---------------------------------------------------------------------
--  B.1 — Ürün birimleri
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS product_units (
    id             INT          NOT NULL AUTO_INCREMENT,
    code           VARCHAR(32)  NOT NULL,
    display_name   VARCHAR(64)  NOT NULL,
    display_order  INT          NOT NULL DEFAULT 0,
    is_active      TINYINT(1)   NOT NULL DEFAULT 1,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_product_units_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- ---------------------------------------------------------------------
--  A.3 — Alan seed'i (13 alan)
-- ---------------------------------------------------------------------
INSERT INTO restaurant_areas (building, floor, salon, display_order, is_active)
SELECT d.building, d.floor, d.salon, d.display_order, 1
FROM (
    SELECT '1. Bina' AS building, '1. Kat' AS floor, '1. Salon' AS salon, 1 AS display_order UNION ALL
    SELECT '1. Bina', '1. Kat', '2. Salon', 2 UNION ALL
    SELECT '1. Bina', '2. Kat', '1. Salon', 3 UNION ALL
    SELECT '1. Bina', '2. Kat', '2. Salon', 4 UNION ALL
    SELECT '1. Bina', '3. Kat', '1. Salon', 5 UNION ALL
    SELECT '1. Bina', '3. Kat', '2. Salon', 6 UNION ALL
    SELECT '2. Bina', '1. Kat', '1. Salon', 7 UNION ALL
    SELECT '2. Bina', '1. Kat', '2. Salon', 8 UNION ALL
    SELECT '2. Bina', '2. Kat', '1. Salon', 9 UNION ALL
    SELECT '2. Bina', '2. Kat', '2. Salon', 10 UNION ALL
    SELECT '2. Bina', '3. Kat', '1. Salon', 11 UNION ALL
    SELECT '2. Bina', '3. Kat', '2. Salon', 12 UNION ALL
    SELECT '3. Bina', 'Bahçe', '', 13
) AS d
WHERE NOT EXISTS (
    SELECT 1 FROM restaurant_areas a
    WHERE a.building = d.building AND a.floor = d.floor AND a.salon = d.salon
);

-- ---------------------------------------------------------------------
--  A.4 — Masa seed'i (70 masa, alanlara bağlı)
--        Koordinatlar bilinçli olarak NULL: yerleşim editörü sonraki aşama.
-- ---------------------------------------------------------------------
INSERT INTO restaurant_table_layout (table_no, area_id, display_order, is_active)
SELECT d.table_no, a.id, d.display_order, 1
FROM (
    SELECT 101 AS table_no, '1. Bina' AS building, '1. Kat' AS floor, '1. Salon' AS salon, 1 AS display_order UNION ALL
    SELECT 102, '1. Bina', '1. Kat', '1. Salon', 2 UNION ALL
    SELECT 103, '1. Bina', '1. Kat', '1. Salon', 3 UNION ALL
    SELECT 104, '1. Bina', '1. Kat', '1. Salon', 4 UNION ALL
    SELECT 105, '1. Bina', '1. Kat', '1. Salon', 5 UNION ALL
    SELECT 106, '1. Bina', '1. Kat', '2. Salon', 1 UNION ALL
    SELECT 107, '1. Bina', '1. Kat', '2. Salon', 2 UNION ALL
    SELECT 108, '1. Bina', '1. Kat', '2. Salon', 3 UNION ALL
    SELECT 109, '1. Bina', '1. Kat', '2. Salon', 4 UNION ALL
    SELECT 110, '1. Bina', '1. Kat', '2. Salon', 5 UNION ALL
    SELECT 111, '1. Bina', '2. Kat', '1. Salon', 1 UNION ALL
    SELECT 112, '1. Bina', '2. Kat', '1. Salon', 2 UNION ALL
    SELECT 113, '1. Bina', '2. Kat', '1. Salon', 3 UNION ALL
    SELECT 114, '1. Bina', '2. Kat', '1. Salon', 4 UNION ALL
    SELECT 115, '1. Bina', '2. Kat', '1. Salon', 5 UNION ALL
    SELECT 116, '1. Bina', '2. Kat', '2. Salon', 1 UNION ALL
    SELECT 117, '1. Bina', '2. Kat', '2. Salon', 2 UNION ALL
    SELECT 118, '1. Bina', '2. Kat', '2. Salon', 3 UNION ALL
    SELECT 119, '1. Bina', '2. Kat', '2. Salon', 4 UNION ALL
    SELECT 120, '1. Bina', '2. Kat', '2. Salon', 5 UNION ALL
    SELECT 121, '1. Bina', '3. Kat', '1. Salon', 1 UNION ALL
    SELECT 122, '1. Bina', '3. Kat', '1. Salon', 2 UNION ALL
    SELECT 123, '1. Bina', '3. Kat', '1. Salon', 3 UNION ALL
    SELECT 124, '1. Bina', '3. Kat', '1. Salon', 4 UNION ALL
    SELECT 125, '1. Bina', '3. Kat', '1. Salon', 5 UNION ALL
    SELECT 126, '1. Bina', '3. Kat', '2. Salon', 1 UNION ALL
    SELECT 127, '1. Bina', '3. Kat', '2. Salon', 2 UNION ALL
    SELECT 128, '1. Bina', '3. Kat', '2. Salon', 3 UNION ALL
    SELECT 129, '1. Bina', '3. Kat', '2. Salon', 4 UNION ALL
    SELECT 130, '1. Bina', '3. Kat', '2. Salon', 5 UNION ALL
    SELECT 201, '2. Bina', '1. Kat', '1. Salon', 1 UNION ALL
    SELECT 202, '2. Bina', '1. Kat', '1. Salon', 2 UNION ALL
    SELECT 203, '2. Bina', '1. Kat', '1. Salon', 3 UNION ALL
    SELECT 204, '2. Bina', '1. Kat', '1. Salon', 4 UNION ALL
    SELECT 205, '2. Bina', '1. Kat', '1. Salon', 5 UNION ALL
    SELECT 206, '2. Bina', '1. Kat', '2. Salon', 1 UNION ALL
    SELECT 207, '2. Bina', '1. Kat', '2. Salon', 2 UNION ALL
    SELECT 208, '2. Bina', '1. Kat', '2. Salon', 3 UNION ALL
    SELECT 209, '2. Bina', '1. Kat', '2. Salon', 4 UNION ALL
    SELECT 210, '2. Bina', '1. Kat', '2. Salon', 5 UNION ALL
    SELECT 211, '2. Bina', '2. Kat', '1. Salon', 1 UNION ALL
    SELECT 212, '2. Bina', '2. Kat', '1. Salon', 2 UNION ALL
    SELECT 213, '2. Bina', '2. Kat', '1. Salon', 3 UNION ALL
    SELECT 214, '2. Bina', '2. Kat', '1. Salon', 4 UNION ALL
    SELECT 215, '2. Bina', '2. Kat', '1. Salon', 5 UNION ALL
    SELECT 216, '2. Bina', '2. Kat', '2. Salon', 1 UNION ALL
    SELECT 217, '2. Bina', '2. Kat', '2. Salon', 2 UNION ALL
    SELECT 218, '2. Bina', '2. Kat', '2. Salon', 3 UNION ALL
    SELECT 219, '2. Bina', '2. Kat', '2. Salon', 4 UNION ALL
    SELECT 220, '2. Bina', '2. Kat', '2. Salon', 5 UNION ALL
    SELECT 221, '2. Bina', '3. Kat', '1. Salon', 1 UNION ALL
    SELECT 222, '2. Bina', '3. Kat', '1. Salon', 2 UNION ALL
    SELECT 223, '2. Bina', '3. Kat', '1. Salon', 3 UNION ALL
    SELECT 224, '2. Bina', '3. Kat', '1. Salon', 4 UNION ALL
    SELECT 225, '2. Bina', '3. Kat', '1. Salon', 5 UNION ALL
    SELECT 226, '2. Bina', '3. Kat', '2. Salon', 1 UNION ALL
    SELECT 227, '2. Bina', '3. Kat', '2. Salon', 2 UNION ALL
    SELECT 228, '2. Bina', '3. Kat', '2. Salon', 3 UNION ALL
    SELECT 229, '2. Bina', '3. Kat', '2. Salon', 4 UNION ALL
    SELECT 230, '2. Bina', '3. Kat', '2. Salon', 5 UNION ALL
    SELECT 301, '3. Bina', 'Bahçe', '', 1 UNION ALL
    SELECT 302, '3. Bina', 'Bahçe', '', 2 UNION ALL
    SELECT 303, '3. Bina', 'Bahçe', '', 3 UNION ALL
    SELECT 304, '3. Bina', 'Bahçe', '', 4 UNION ALL
    SELECT 305, '3. Bina', 'Bahçe', '', 5 UNION ALL
    SELECT 306, '3. Bina', 'Bahçe', '', 6 UNION ALL
    SELECT 307, '3. Bina', 'Bahçe', '', 7 UNION ALL
    SELECT 308, '3. Bina', 'Bahçe', '', 8 UNION ALL
    SELECT 309, '3. Bina', 'Bahçe', '', 9 UNION ALL
    SELECT 310, '3. Bina', 'Bahçe', '', 10
) AS d
JOIN restaurant_areas a
  ON a.building = d.building AND a.floor = d.floor AND a.salon = d.salon
WHERE NOT EXISTS (
    SELECT 1 FROM restaurant_table_layout l WHERE l.table_no = d.table_no
);

-- ---------------------------------------------------------------------
--  B.2 — Birim seed'i (koddaki hardcoded listenin DB karşılığı)
-- ---------------------------------------------------------------------
INSERT INTO product_units (code, display_name, display_order, is_active)
SELECT d.code, d.display_name, d.display_order, 1
FROM (
    SELECT 'porsiyon' AS code, 'porsiyon' AS display_name, 1 AS display_order UNION ALL
    SELECT 'şiş', 'şiş', 2 UNION ALL
    SELECT 'adet', 'adet', 3 UNION ALL
    SELECT 'kg', 'kg', 4 UNION ALL
    SELECT 'tabak', 'tabak', 5 UNION ALL
    SELECT 'kase', 'kase', 6
) AS d
WHERE NOT EXISTS (SELECT 1 FROM product_units u WHERE u.code = d.code);

-- ---------------------------------------------------------------------
--  B.3 — Mevcut ürünlerdeki ÖZEL birim değerlerini aktar
--        products tablosu OKUNUR, değiştirilmez. Listede olmayan her
--        unit_label pasif değil AKTİF olarak eklenir; aksi halde o ürünün
--        birimi düzenleme ekranında seçilemez duruma düşerdi.
-- ---------------------------------------------------------------------
INSERT INTO product_units (code, display_name, display_order, is_active)
SELECT d.code, d.code, 100, 1
FROM (
    SELECT DISTINCT TRIM(p.unit_label) AS code
    FROM products p
    WHERE p.unit_label IS NOT NULL AND TRIM(p.unit_label) <> ''
) AS d
WHERE NOT EXISTS (SELECT 1 FROM product_units u WHERE u.code = d.code);

-- =====================================================================
--  V004 sonu
-- =====================================================================
