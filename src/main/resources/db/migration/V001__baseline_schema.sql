-- =====================================================================
--  V001 — Canonical baseline şeması (budgetController)
--
--  KAYNAK: DAO / service / model sözleşmeleri (rs.getXxx, INSERT/UPDATE
--  kolon listeleri, enum'lar) + eski migration DDL'leri. Üretim dump'ından
--  KOPYALANMAMIŞTIR; yalnız tip uyumu (INT id'ler, enum setleri) mevcut
--  kurulumla adoption için hizalanmıştır.
--
--  KURALLAR
--    * Her ifade idempotent (CREATE TABLE IF NOT EXISTS). Runner (SchemaMigrator)
--      dosyayı noktalı virgülle böler. DELIMITER / routine KULLANILMAZ ve
--      string literal / COMMENT içinde noktalı virgül BULUNMAMALIDIR.
--    * Veri içermez. Seed'ler V002+ dosyalarındadır.
--    * products üzerinde CHECK constraint YOKTUR (uygulama stok yönetmez;
--      eski şemadaki stock/vat CHECK'leri sipariş eklemeyi patlatıyordu).
--    * Karakter seti: utf8mb4 / utf8mb4_turkish_ci (tüm tablolar).
--    * Bu dosya yalnız migration sürecinde (budget_migrate) çalışır; runtime
--      kullanıcısı budget_app DDL yetkisi taşımaz.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) Referans: roller ve kullanıcılar
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS roles (
    id          INT          NOT NULL AUTO_INCREMENT,
    name        VARCHAR(32)  NOT NULL COMMENT 'Role enum: ADMIN | KASIYER | GARSON',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_roles_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE TABLE IF NOT EXISTS users (
    id             INT          NOT NULL AUTO_INCREMENT,
    username       VARCHAR(64)  NOT NULL,
    password_hash  VARCHAR(100) NULL     COMMENT 'BCrypt ($2a$...). NULL/eski hash → giriş reddedilir, admin yeni parola atar',
    full_name      VARCHAR(120) NULL,
    role_id        INT          NOT NULL,
    is_active      TINYINT(1)   NOT NULL DEFAULT 1,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_username (username),
    KEY idx_users_role (role_id),
    CONSTRAINT fk_users_role FOREIGN KEY (role_id) REFERENCES roles (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- ---------------------------------------------------------------------
-- 2) Katalog
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS categories (
    id          INT          NOT NULL AUTO_INCREMENT,
    name        VARCHAR(80)  NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_categories_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE TABLE IF NOT EXISTS products (
    id                  INT           NOT NULL AUTO_INCREMENT,
    name                VARCHAR(150)  NOT NULL,
    unit_price          DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'Porsiyon fiyatı',
    stock_qty           INT           NOT NULL DEFAULT 0    COMMENT 'Uygulama stok yönetmez - bilgi amaçlı, negatif olabilir, CHECK YOK',
    category_id         INT           NULL,
    is_active           TINYINT(1)    NOT NULL DEFAULT 1    COMMENT '0 = TÜKENDİ (pasif)',
    pieces_per_portion  INT           NULL                  COMMENT '1 porsiyonda kaç birim (şiş)? NULL → porsiyon bazlı',
    unit_label          VARCHAR(16)   NULL                  COMMENT 'porsiyon / şiş / kg / adet',
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_products_category (category_id),
    KEY idx_products_name (name),
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- ---------------------------------------------------------------------
-- 3) Masalar (satırlar restaurant-layout.properties'ten uygulama tarafından
--    otomatik oluşturulur — seed gerekmez)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dining_tables (
    id          INT          NOT NULL AUTO_INCREMENT,
    table_no    INT          NOT NULL,
    status      ENUM('EMPTY','OCCUPIED','RESERVED') NOT NULL DEFAULT 'EMPTY',
    note        VARCHAR(120) NULL COMMENT 'Bina / salon etiketi',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_dining_tables_no (table_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- ---------------------------------------------------------------------
-- 4) Mutfak yazıcıları (satırlar V003 seed'i ile placeholder olarak gelir)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kitchen_printers (
    id             INT          NOT NULL AUTO_INCREMENT,
    code           VARCHAR(32)  NOT NULL COMMENT 'Örn: DONER, FIRIN, OCAK',
    display_name   VARCHAR(80)  NOT NULL COMMENT 'Fiş başlığında basılır',
    host           VARCHAR(120) NOT NULL COMMENT 'IP veya hostname (TCP 9100)',
    port           INT          NOT NULL DEFAULT 9100,
    char_per_line  TINYINT      NOT NULL DEFAULT 42 COMMENT '80mm: 42, 58mm: 32',
    code_page      SMALLINT     NOT NULL DEFAULT 12 COMMENT 'ESC t n — 12 = CP857 (Türkçe)',
    is_active      TINYINT(1)   NOT NULL DEFAULT 1,
    note           VARCHAR(255) NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_kitchen_printers_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- ---------------------------------------------------------------------
-- 5) Sipariş çekirdeği
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS orders (
    id              INT           NOT NULL AUTO_INCREMENT,
    table_id        INT           NULL,
    waiter_id       INT           NULL,
    note            VARCHAR(255)  NULL,
    status          ENUM('PENDING','IN_PROGRESS','READY','COMPLETED','CANCELLED') NOT NULL DEFAULT 'PENDING',
    subtotal        DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    tax_total       DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    discount_total  DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    total           DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    order_date      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at       DATETIME      NULL,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_orders_table_status (table_id, status),
    KEY idx_orders_order_date (order_date),
    KEY idx_orders_waiter (waiter_id),
    CONSTRAINT fk_orders_table  FOREIGN KEY (table_id)  REFERENCES dining_tables (id) ON DELETE SET NULL,
    CONSTRAINT fk_orders_waiter FOREIGN KEY (waiter_id) REFERENCES users (id)         ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE TABLE IF NOT EXISTS order_items (
    id                   INT           NOT NULL AUTO_INCREMENT,
    order_id             INT           NOT NULL,
    product_id           INT           NOT NULL,
    product_name         VARCHAR(150)  NULL COMMENT 'Sipariş anı snapshot',
    quantity             INT           NOT NULL COMMENT 'Şiş bazlı üründe toplam şiş sayısı',
    unit_price           DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'Şiş bazlıysa şiş başına fiyat',
    net_amount           DECIMAL(12,2) GENERATED ALWAYS AS (ROUND(quantity * unit_price, 2)) STORED,
    tax_amount           DECIMAL(12,2) GENERATED ALWAYS AS (0.00) STORED,
    line_total           DECIMAL(12,2) GENERATED ALWAYS AS (ROUND(quantity * unit_price, 2)) STORED,
    printed_at           DATETIME      NULL COMMENT 'Mutfağa basıldı (NULL = YENİ/pending)',
    print_count          SMALLINT      NOT NULL DEFAULT 0,
    kitchen_override_id  INT           NULL COMMENT 'Satır bazlı mutfak override',
    pieces_per_portion   INT           NULL COMMENT 'Sipariş anı snapshot',
    unit_label           VARCHAR(16)   NULL COMMENT 'Sipariş anı snapshot',
    note                 VARCHAR(255)  NULL COMMENT 'Kalem notu (örn. soğansız)',
    created_at           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_order_items_order   (order_id),
    KEY idx_order_items_product (product_id),
    CONSTRAINT chk_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT fk_order_items_order    FOREIGN KEY (order_id)   REFERENCES orders (id)   ON DELETE CASCADE,
    CONSTRAINT fk_order_items_product  FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_items_kitchen  FOREIGN KEY (kitchen_override_id) REFERENCES kitchen_printers (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE TABLE IF NOT EXISTS payments (
    id          INT           NOT NULL AUTO_INCREMENT,
    order_id    INT           NOT NULL,
    cashier_id  INT           NULL,
    amount      DECIMAL(12,2) NOT NULL,
    method      ENUM('CASH','CREDIT_CARD','DEBIT_CARD','TRANSFER','ONLINE','MIXED') NOT NULL,
    paid_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_payments_order   (order_id),
    KEY idx_payments_paid_at (paid_at),
    CONSTRAINT fk_payments_order   FOREIGN KEY (order_id)   REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_payments_cashier FOREIGN KEY (cashier_id) REFERENCES users (id)  ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE TABLE IF NOT EXISTS order_logs (
    id          INT          NOT NULL AUTO_INCREMENT,
    order_id    INT          NOT NULL,
    event_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    message     VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_order_logs_order_time (order_id, event_time),
    CONSTRAINT fk_order_logs_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- ---------------------------------------------------------------------
-- 6) Giderler
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS expenses (
    id                 INT           NOT NULL AUTO_INCREMENT,
    expense_date       DATE          NOT NULL DEFAULT (CURDATE()),
    amount             DECIMAL(12,2) NOT NULL,
    note               VARCHAR(500)  NULL,
    quantity_kg        DECIMAL(10,3) NULL COMMENT 'Kg-bazlı giriş: miktar',
    unit_price_per_kg  DECIMAL(10,2) NULL COMMENT 'Kg-bazlı giriş: 1 kg fiyatı',
    created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_expenses_date (expense_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- ---------------------------------------------------------------------
-- 7) Audit: iade / azaltma / silme kayıtları
--    (RefundLogJdbcDAO'daki DDL ile birebir; runtime CREATE artık burada)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS refund_log (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    user_id       BIGINT        NULL,
    user_name     VARCHAR(255)  NULL,
    action_type   VARCHAR(32)   NOT NULL COMMENT 'DECREASE_ITEM | REMOVE_ITEM | CLEAR_TABLE ...',
    table_no      INT           NULL,
    order_id      BIGINT        NULL,
    product_name  VARCHAR(255)  NULL,
    quantity      INT           NULL,
    amount        DECIMAL(19,2) NULL,
    reason        VARCHAR(500)  NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_refund_created (created_at),
    KEY idx_refund_user    (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- ---------------------------------------------------------------------
-- 8) Rezervasyonlar (SchemaPatcher DDL'i ile birebir; runtime CREATE artık burada)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS reservations (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    table_no        INT          NOT NULL,
    start_time      DATETIME     NOT NULL,
    end_time        DATETIME     NOT NULL,
    customer_name   VARCHAR(120) NOT NULL,
    customer_phone  VARCHAR(40)  NULL,
    party_size      INT          NULL DEFAULT 1,
    notes           VARCHAR(500) NULL,
    status          VARCHAR(20)  NULL DEFAULT 'BOOKED' COMMENT 'BOOKED | CANCELLED | SEATED | NO_SHOW',
    created_at      DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(80)  NULL,
    PRIMARY KEY (id),
    KEY idx_reservations_table_time (table_no, start_time, end_time),
    KEY idx_reservations_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- ---------------------------------------------------------------------
-- 9) Mutfak yönlendirme + baskı kuyruğu
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS category_printer_routes (
    id           INT       NOT NULL AUTO_INCREMENT,
    category_id  INT       NOT NULL,
    printer_id   INT       NOT NULL,
    created_at   DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_cpr_cat_pr (category_id, printer_id),
    KEY idx_cpr_category (category_id),
    KEY idx_cpr_printer  (printer_id),
    CONSTRAINT fk_cpr_category FOREIGN KEY (category_id) REFERENCES categories (id)       ON DELETE CASCADE,
    CONSTRAINT fk_cpr_printer  FOREIGN KEY (printer_id)  REFERENCES kitchen_printers (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

CREATE TABLE IF NOT EXISTS print_jobs (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    order_id    INT          NOT NULL,
    printer_id  INT          NOT NULL,
    payload     LONGTEXT     NOT NULL COMMENT 'Fiş JSON gövdesi (Receipt)',
    status      ENUM('PENDING','PRINTED','FAILED') NOT NULL DEFAULT 'PENDING',
    attempts    INT          NOT NULL DEFAULT 0,
    last_error  VARCHAR(500) NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    printed_at  DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_pj_status (status, created_at),
    KEY idx_pj_order  (order_id),
    CONSTRAINT fk_pj_order   FOREIGN KEY (order_id)   REFERENCES orders (id)           ON DELETE CASCADE,
    CONSTRAINT fk_pj_printer FOREIGN KEY (printer_id) REFERENCES kitchen_printers (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- ---------------------------------------------------------------------
-- 10) Garson alan yetkileri
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_area_permissions (
    id          INT          NOT NULL AUTO_INCREMENT,
    user_id     INT          NOT NULL,
    building    VARCHAR(64)  NOT NULL COMMENT 'Örn: "1. Bina"',
    section     VARCHAR(64)  NOT NULL COMMENT 'Örn: "2. Kat" / "Bahçe"',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_uap_user_area (user_id, building, section),
    KEY idx_uap_user (user_id),
    CONSTRAINT fk_uap_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_turkish_ci;

-- =====================================================================
--  V001 sonu — 16 tablo: roles, users, categories, products, dining_tables,
--  kitchen_printers, orders, order_items, payments, order_logs, expenses,
--  refund_log, reservations, category_printer_routes, print_jobs,
--  user_area_permissions
-- =====================================================================
