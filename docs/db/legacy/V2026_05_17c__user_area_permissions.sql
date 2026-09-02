-- =====================================================================
--  [LEGACY ARŞİV — ÇALIŞTIRMAYIN] Canonical karşılığı: V001
--  (user_area_permissions — baseline'da DROP'suz, IF NOT EXISTS ile).
--  NOT: Bu dosya destructive bir DROP TABLE içeriyordu; runner sistemine
--  alınmamasının bir nedeni de budur.
-- =====================================================================
--  Tarih   : 2026-05-17 (C)
--  Amaç    : Garson alan yetkilendirmesi. Bir garson sadece kendisine
--            atanmış (bina, salon) çiftlerini görür; Admin/Kasiyer tümünü.
--  NOT     : users.id INT olduğu için user_id INT (FK tip eşleşmesi).
-- =====================================================================

DROP TABLE IF EXISTS user_area_permissions;

CREATE TABLE user_area_permissions (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    user_id      INT          NOT NULL,
    building     VARCHAR(64)  NOT NULL  COMMENT 'Örn: "1. Bina"',
    section      VARCHAR(64)  NOT NULL  COMMENT 'Örn: "2. Kat" / "Bahçe"',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_uap_user_area UNIQUE (user_id, building, section),
    CONSTRAINT fk_uap_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE INDEX idx_uap_user ON user_area_permissions (user_id);
