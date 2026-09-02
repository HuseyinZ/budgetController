-- =====================================================================
--  [LEGACY ARŞİV — ÇALIŞTIRMAYIN] Sanitize edildi: IP'ler RFC 5737 (192.0.2.x).
--  Canonical karşılığı: src/main/resources/db/migration/V001 + V003.
-- =====================================================================
--  Mutfak Fiş Yazıcı sistemi için şema eklemeleri
--  Tarih   : 2026-05-15
--  Amaç    : Sipariş alındığında kategoriye göre doğru mutfağa
--            otomatik fiş gönderimi.
-- =====================================================================

-- 1) Mutfak yazıcı tanımları
CREATE TABLE IF NOT EXISTS kitchen_printers (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    code          VARCHAR(32)  NOT NULL UNIQUE COMMENT 'Örn: KITCHEN_1, KITCHEN_DONER',
    display_name  VARCHAR(80)  NOT NULL        COMMENT 'Fiş başlığında basılır',
    host          VARCHAR(120) NOT NULL        COMMENT 'IP veya hostname',
    port          INT          NOT NULL DEFAULT 9100,
    char_per_line TINYINT      NOT NULL DEFAULT 42 COMMENT '80mm yazıcı için 42, 58mm için 32',
    code_page     SMALLINT     NOT NULL DEFAULT 12  COMMENT 'ESC t n  — 12 = CP857 (Türkçe)',
    is_active     TINYINT(1)   NOT NULL DEFAULT 1,
    note          VARCHAR(255) NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- 2) Kategori → Yazıcı eşleştirmesi (çoklu mutfak yönlendirmesi)
CREATE TABLE IF NOT EXISTS category_printer_routes (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    category_id  INT    NOT NULL,
    printer_id   INT    NOT NULL,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_cpr_cat_pr UNIQUE (category_id, printer_id),
    CONSTRAINT fk_cpr_category FOREIGN KEY (category_id)
        REFERENCES categories(id) ON DELETE CASCADE,
    CONSTRAINT fk_cpr_printer  FOREIGN KEY (printer_id)
        REFERENCES kitchen_printers(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE INDEX idx_cpr_category ON category_printer_routes (category_id);
CREATE INDEX idx_cpr_printer  ON category_printer_routes (printer_id);

-- 3) Fiş kuyruğu — başarısız fişler ve yeniden deneme
CREATE TABLE IF NOT EXISTS print_jobs (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id     INT    NOT NULL,
    printer_id   INT    NOT NULL,
    payload      LONGTEXT NOT NULL COMMENT 'Fiş JSON gövdesi',
    status       ENUM('PENDING','PRINTED','FAILED') NOT NULL DEFAULT 'PENDING',
    attempts     INT NOT NULL DEFAULT 0,
    last_error   VARCHAR(500) NULL,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    printed_at   DATETIME NULL,
    CONSTRAINT fk_pj_order   FOREIGN KEY (order_id)   REFERENCES orders(id)  ON DELETE CASCADE,
    CONSTRAINT fk_pj_printer FOREIGN KEY (printer_id) REFERENCES kitchen_printers(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE INDEX idx_pj_status ON print_jobs (status, created_at);
CREATE INDEX idx_pj_order  ON print_jobs (order_id);

-- 4) order_items üstüne idempotency işaretleri
ALTER TABLE order_items
  ADD COLUMN printed_at   DATETIME NULL,
  ADD COLUMN print_count  SMALLINT NOT NULL DEFAULT 0;

-- 5) Örnek başlangıç kayıtları (DEMO — host değerleri sanitize edildi)
INSERT INTO kitchen_printers (code, display_name, host, port, char_per_line, note)
SELECT * FROM (SELECT 'KITCHEN_1' AS code, 'Mutfak 1 - Sıcak' AS display_name,
                      '192.0.2.1' AS host, 9100 AS port, 42 AS char_per_line,
                      'Ciğer, ızgara, sıcak yemekler' AS note) AS d
WHERE NOT EXISTS (SELECT 1 FROM kitchen_printers WHERE code = 'KITCHEN_1');

INSERT INTO kitchen_printers (code, display_name, host, port, char_per_line, note)
SELECT * FROM (SELECT 'KITCHEN_2' AS code, 'Mutfak 2 - Döner' AS display_name,
                      '192.0.2.2' AS host, 9100 AS port, 42 AS char_per_line,
                      'Döner, dürüm, kebap' AS note) AS d
WHERE NOT EXISTS (SELECT 1 FROM kitchen_printers WHERE code = 'KITCHEN_2');

-- Yardımcı görünüm (V001'e ALINMADI — kod kullanmıyor)
DROP VIEW IF EXISTS v_pending_print_jobs;
CREATE VIEW v_pending_print_jobs AS
SELECT pj.id, pj.order_id, kp.code AS printer_code, kp.display_name AS printer_name,
       pj.status, pj.attempts, pj.last_error, pj.created_at
FROM print_jobs pj
JOIN kitchen_printers kp ON kp.id = pj.printer_id
WHERE pj.status IN ('PENDING','FAILED')
ORDER BY pj.created_at ASC;
