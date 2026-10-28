-- =============================================================
-- V1__init_schema.sql
-- Khoi tao toan bo schema GreenMart (28 bang)
-- Phu trach: SV03 - Dat9161
-- Tham chieu: ERD 00-erd-day-du.puml, SRS v1.0 muc 7
-- =============================================================

-- ---------------------------------------------------------------
-- 1. DANH MUC VA TO CHUC
-- ---------------------------------------------------------------

CREATE TABLE branch (
    id         BIGSERIAL    PRIMARY KEY,
    code       VARCHAR(20)  NOT NULL UNIQUE,
    name       VARCHAR(120) NOT NULL,
    address    VARCHAR(255),
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE category (
    id        BIGSERIAL    PRIMARY KEY,
    name      VARCHAR(120) NOT NULL,
    parent_id BIGINT       REFERENCES category(id) ON DELETE SET NULL
);

CREATE TABLE product (
    id          BIGSERIAL    PRIMARY KEY,
    sku         VARCHAR(40)  NOT NULL UNIQUE,
    barcode     VARCHAR(40),
    name        VARCHAR(200) NOT NULL,
    unit        VARCHAR(20)  NOT NULL,
    category_id BIGINT       NOT NULL REFERENCES category(id),
    sale_price  BIGINT       NOT NULL CHECK (sale_price >= 0),
    cost_price  BIGINT       NOT NULL CHECK (cost_price >= 0),
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE
);

-- ---------------------------------------------------------------
-- 2. NGUOI DUNG VA PHAN QUYEN
-- ---------------------------------------------------------------

CREATE TABLE app_user (
    id                 BIGSERIAL    PRIMARY KEY,
    username           VARCHAR(60)  NOT NULL UNIQUE,
    password_hash      VARCHAR(255) NOT NULL,
    full_name          VARCHAR(120) NOT NULL,
    -- Dung VARCHAR+CHECK thay vi PostgreSQL enum de tuong thich JPA @Enumerated(STRING)
    role               VARCHAR(20)  NOT NULL CHECK (role IN ('ADMIN','OWNER','BRANCH_MANAGER','CASHIER','STOREKEEPER')),
    is_active          BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_count INT          NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
    locked_until       TIMESTAMPTZ
);

CREATE TABLE user_branch (
    user_id   BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    branch_id BIGINT NOT NULL REFERENCES branch(id)   ON DELETE CASCADE,
    PRIMARY KEY (user_id, branch_id)
);

CREATE TABLE audit_log (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      REFERENCES app_user(id) ON DELETE SET NULL,
    action     VARCHAR(60) NOT NULL,
    entity     VARCHAR(60) NOT NULL,
    entity_id  VARCHAR(60),
    detail     JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- audit_log chi INSERT, khong UPDATE/DELETE

-- ---------------------------------------------------------------
-- 3. TICH HOP KENH
-- ---------------------------------------------------------------

CREATE TABLE sales_channel (
    id                BIGSERIAL    PRIMARY KEY,
    code              VARCHAR(10)  NOT NULL UNIQUE CHECK (code IN ('POS','WEB','MARKET')),
    name              VARCHAR(60)  NOT NULL,
    default_branch_id BIGINT       REFERENCES branch(id) ON DELETE SET NULL,
    webhook_secret    VARCHAR(100),
    safety_stock      INT          NOT NULL DEFAULT 2 CHECK (safety_stock >= 0)
);

CREATE TABLE channel_branch (
    channel_id BIGINT NOT NULL REFERENCES sales_channel(id) ON DELETE CASCADE,
    branch_id  BIGINT NOT NULL REFERENCES branch(id)        ON DELETE CASCADE,
    PRIMARY KEY (channel_id, branch_id)
);

CREATE TABLE channel_product (
    channel_id   BIGINT      NOT NULL REFERENCES sales_channel(id) ON DELETE CASCADE,
    product_id   BIGINT      NOT NULL REFERENCES product(id)       ON DELETE CASCADE,
    external_sku VARCHAR(60) NOT NULL,
    PRIMARY KEY (channel_id, product_id),
    UNIQUE (channel_id, external_sku)
);

-- ---------------------------------------------------------------
-- 4. BAN HANG
-- ---------------------------------------------------------------

CREATE TABLE customer (
    id         BIGSERIAL    PRIMARY KEY,
    phone      VARCHAR(15)  NOT NULL UNIQUE,
    name       VARCHAR(120) NOT NULL,
    email      VARCHAR(120),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE promotion (
    id           BIGSERIAL   PRIMARY KEY,
    code         VARCHAR(30) NOT NULL UNIQUE,
    type         VARCHAR(10) NOT NULL CHECK (type IN ('PERCENT','AMOUNT')),
    level        VARCHAR(10) NOT NULL CHECK (level IN ('ORDER','ITEM')),
    value        BIGINT      NOT NULL CHECK (value > 0),
    max_discount BIGINT,
    min_order    BIGINT,
    start_at     TIMESTAMPTZ NOT NULL,
    end_at       TIMESTAMPTZ NOT NULL CHECK (end_at > start_at),
    usage_limit  INT,
    used_count   INT         NOT NULL DEFAULT 0,
    status       VARCHAR(10) NOT NULL DEFAULT 'DRAFT'
                     CHECK (status IN ('DRAFT','ACTIVE','PAUSED','ENDED')),
    CONSTRAINT chk_used_limit CHECK (usage_limit IS NULL OR used_count <= usage_limit)
);

CREATE TABLE promotion_product (
    promotion_id BIGINT NOT NULL REFERENCES promotion(id) ON DELETE CASCADE,
    product_id   BIGINT NOT NULL REFERENCES product(id)   ON DELETE CASCADE,
    PRIMARY KEY (promotion_id, product_id)
);

CREATE TABLE promotion_branch (
    promotion_id BIGINT NOT NULL REFERENCES promotion(id) ON DELETE CASCADE,
    branch_id    BIGINT NOT NULL REFERENCES branch(id)    ON DELETE CASCADE,
    PRIMARY KEY (promotion_id, branch_id)
);

CREATE TABLE promotion_channel (
    promotion_id BIGINT NOT NULL REFERENCES promotion(id)     ON DELETE CASCADE,
    channel_id   BIGINT NOT NULL REFERENCES sales_channel(id) ON DELETE CASCADE,
    PRIMARY KEY (promotion_id, channel_id)
);

CREATE TABLE sales_order (
    id                BIGSERIAL   PRIMARY KEY,
    code              VARCHAR(30) NOT NULL UNIQUE,
    channel_id        BIGINT      NOT NULL REFERENCES sales_channel(id),
    external_order_id VARCHAR(60),
    branch_id         BIGINT      NOT NULL REFERENCES branch(id),
    customer_id       BIGINT      REFERENCES customer(id)  ON DELETE SET NULL,
    promotion_id      BIGINT      REFERENCES promotion(id) ON DELETE SET NULL,
    status            VARCHAR(15) NOT NULL DEFAULT 'NEW'
                          CHECK (status IN ('NEW','RESERVED','CONFIRMED','COMPLETED',
                                            'CANCELLED','EXPIRED','REJECTED','RETURNED')),
    subtotal          BIGINT      NOT NULL CHECK (subtotal >= 0),
    discount          BIGINT      NOT NULL DEFAULT 0 CHECK (discount >= 0),
    total             BIGINT      NOT NULL CHECK (total >= 0),
    reserved_until    TIMESTAMPTZ,
    created_by        BIGINT      REFERENCES app_user(id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at      TIMESTAMPTZ,
    UNIQUE (channel_id, external_order_id)
);

CREATE TABLE order_item (
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT    NOT NULL REFERENCES sales_order(id) ON DELETE CASCADE,
    product_id   BIGINT    NOT NULL REFERENCES product(id),
    quantity     INT       NOT NULL CHECK (quantity > 0),
    unit_price   BIGINT    NOT NULL CHECK (unit_price >= 0),
    discount     BIGINT    NOT NULL DEFAULT 0 CHECK (discount >= 0),
    line_total   BIGINT    NOT NULL CHECK (line_total >= 0),
    promotion_id BIGINT    REFERENCES promotion(id) ON DELETE SET NULL
);

CREATE TABLE payment (
    id           BIGSERIAL   PRIMARY KEY,
    order_id     BIGINT      NOT NULL REFERENCES sales_order(id) ON DELETE CASCADE,
    method       VARCHAR(10) NOT NULL CHECK (method IN ('CASH','CARD','QR')),
    -- amount >= 0: don giam 100% co the co amount = 0 (theo ERD >= 0)
    amount       BIGINT      NOT NULL CHECK (amount >= 0),
    status       VARCHAR(10) NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING','PAID','FAILED','REFUNDED')),
    provider_ref VARCHAR(60),
    paid_at      TIMESTAMPTZ
);

CREATE TABLE return_document (
    id            BIGSERIAL    PRIMARY KEY,
    code          VARCHAR(30)  NOT NULL UNIQUE,
    order_id      BIGINT       NOT NULL REFERENCES sales_order(id),
    branch_id     BIGINT       NOT NULL REFERENCES branch(id),
    reason        VARCHAR(255) NOT NULL,
    refund_amount BIGINT       NOT NULL CHECK (refund_amount >= 0),
    status        VARCHAR(10)  NOT NULL DEFAULT 'DRAFT'
                      CHECK (status IN ('DRAFT','APPROVED','CANCELLED')),
    created_by    BIGINT       NOT NULL REFERENCES app_user(id),
    approved_by   BIGINT       REFERENCES app_user(id)
);

CREATE TABLE return_item (
    id            BIGSERIAL   PRIMARY KEY,
    return_id     BIGINT      NOT NULL REFERENCES return_document(id) ON DELETE CASCADE,
    order_item_id BIGINT      NOT NULL REFERENCES order_item(id),
    quantity      INT         NOT NULL CHECK (quantity > 0),
    condition     VARCHAR(15) NOT NULL CHECK (condition IN ('RESELLABLE','DAMAGED'))
);

-- ---------------------------------------------------------------
-- 5. KHO
-- ---------------------------------------------------------------

CREATE TABLE inventory (
    branch_id     BIGINT      NOT NULL REFERENCES branch(id),
    product_id    BIGINT      NOT NULL REFERENCES product(id),
    on_hand       INT         NOT NULL DEFAULT 0,
    reserved      INT         NOT NULL DEFAULT 0,
    reorder_point INT,
    version       BIGINT      NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (branch_id, product_id),
    CONSTRAINT chk_on_hand_non_negative  CHECK (on_hand >= 0),
    CONSTRAINT chk_reserved_non_negative CHECK (reserved >= 0),
    CONSTRAINT chk_reserved_le_on_hand   CHECK (reserved <= on_hand)
);

CREATE TABLE inventory_transaction (
    id            BIGSERIAL   PRIMARY KEY,
    branch_id     BIGINT      NOT NULL REFERENCES branch(id),
    product_id    BIGINT      NOT NULL REFERENCES product(id),
    -- quantity co the am (EXPORT/SALE/RESERVE tru ton), khong them CHECK
    type          VARCHAR(15) NOT NULL CHECK (type IN (
                      'IMPORT','EXPORT','SALE','RESERVE','RELEASE',
                      'RETURN','TRANSFER_OUT','TRANSFER_IN','ADJUST')),
    quantity      INT         NOT NULL,
    balance_after INT         NOT NULL,
    ref_type      VARCHAR(30) NOT NULL,
    ref_id        BIGINT      NOT NULL,
    created_by    BIGINT      REFERENCES app_user(id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- inventory_transaction chi INSERT, khong UPDATE/DELETE

CREATE TABLE stock_document (
    id           BIGSERIAL   PRIMARY KEY,
    code         VARCHAR(30) NOT NULL UNIQUE,
    type         VARCHAR(10) NOT NULL CHECK (type IN ('IMPORT','EXPORT','TRANSFER','ADJUST')),
    branch_id    BIGINT      NOT NULL REFERENCES branch(id),
    to_branch_id BIGINT      REFERENCES branch(id),
    status       VARCHAR(15) NOT NULL DEFAULT 'DRAFT'
                     CHECK (status IN ('DRAFT','APPROVED','IN_TRANSIT','RECEIVED','CANCELLED')),
    supplier     VARCHAR(120),
    reason       VARCHAR(255),
    created_by   BIGINT      NOT NULL REFERENCES app_user(id),
    approved_by  BIGINT      REFERENCES app_user(id),
    received_by  BIGINT      REFERENCES app_user(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_approver_ne_creator CHECK (approved_by IS NULL OR approved_by <> created_by),
    -- TRANSFER phai co to_branch_id khac branch_id
    CONSTRAINT chk_transfer_needs_target
        CHECK (type <> 'TRANSFER' OR (to_branch_id IS NOT NULL AND to_branch_id <> branch_id))
);

CREATE TABLE stock_document_item (
    id                BIGSERIAL PRIMARY KEY,
    document_id       BIGINT    NOT NULL REFERENCES stock_document(id) ON DELETE CASCADE,
    product_id        BIGINT    NOT NULL REFERENCES product(id),
    quantity          INT       NOT NULL CHECK (quantity > 0),
    received_quantity INT       CHECK (received_quantity >= 0),
    counted_quantity  INT       CHECK (counted_quantity >= 0),
    unit_cost         BIGINT    CHECK (unit_cost >= 0)
);

-- ---------------------------------------------------------------
-- 6. OUTBOX VA SYNC LOG
-- ---------------------------------------------------------------

CREATE TABLE outbox_event (
    id             BIGSERIAL   PRIMARY KEY,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id   VARCHAR(60) NOT NULL,
    event_type     VARCHAR(60) NOT NULL,
    payload        JSONB       NOT NULL,
    status         VARCHAR(10) NOT NULL DEFAULT 'PENDING'
                       CHECK (status IN ('PENDING','SENT','FAILED')),
    attempts       INT         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE sync_log (
    id          BIGSERIAL   PRIMARY KEY,
    channel_id  BIGINT      NOT NULL REFERENCES sales_channel(id),
    direction   VARCHAR(5)  NOT NULL CHECK (direction IN ('IN','OUT')),
    external_id VARCHAR(60),
    status      VARCHAR(10) NOT NULL CHECK (status IN ('SUCCESS','RETRYING','FAILED')),
    error       TEXT,
    attempts    INT         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------
-- 7. DU BAO (so huu boi forecast-service)
-- ---------------------------------------------------------------

CREATE TABLE daily_sales (
    sale_date          DATE         NOT NULL,
    branch_id          BIGINT       NOT NULL REFERENCES branch(id),
    product_id         BIGINT       NOT NULL REFERENCES product(id),
    qty_sold           INT          NOT NULL CHECK (qty_sold >= 0),
    qty_returned       INT          NOT NULL DEFAULT 0 CHECK (qty_returned >= 0),
    -- qty_net co the am (tra hang nhieu hon ban trong ngay), khong them CHECK
    qty_net            INT          NOT NULL,
    revenue            BIGINT       NOT NULL,
    promo_flag         BOOLEAN      NOT NULL DEFAULT FALSE,
    promo_discount_pct NUMERIC(5,2) NOT NULL DEFAULT 0,
    stockout           BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (sale_date, branch_id, product_id)
);

CREATE TABLE forecast_run (
    id            BIGSERIAL   PRIMARY KEY,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at   TIMESTAMPTZ,
    status        VARCHAR(10) NOT NULL CHECK (status IN ('RUNNING','DONE','FAILED')),
    model_version VARCHAR(40) NOT NULL,
    mae           NUMERIC,
    wape          NUMERIC,
    baseline_mae  NUMERIC,
    baseline_wape NUMERIC
);

CREATE TABLE forecast_result (
    id            BIGSERIAL   PRIMARY KEY,
    run_id        BIGINT      NOT NULL REFERENCES forecast_run(id),
    branch_id     BIGINT      NOT NULL REFERENCES branch(id),
    product_id    BIGINT      NOT NULL REFERENCES product(id),
    horizon       INT         NOT NULL CHECK (horizon IN (7, 30)),
    target_date   DATE        NOT NULL,
    predicted_qty NUMERIC     NOT NULL,
    -- Moi (run, branch, product, horizon, target_date) la duy nhat
    UNIQUE (run_id, branch_id, product_id, horizon, target_date)
);

-- ---------------------------------------------------------------
-- 8. CHI MUC (index)
-- ---------------------------------------------------------------

-- product
CREATE INDEX idx_product_category ON product(category_id);

-- sales_order
CREATE INDEX idx_order_branch       ON sales_order(branch_id);
CREATE INDEX idx_order_channel      ON sales_order(channel_id);
CREATE INDEX idx_order_customer     ON sales_order(customer_id);
CREATE INDEX idx_order_status       ON sales_order(status);
CREATE INDEX idx_order_created_at   ON sales_order(created_at DESC);
-- Bao cao doanh thu theo ngay hoan thanh (BR-18)
CREATE INDEX idx_order_completed_at ON sales_order(completed_at);
-- Job het han dat cho 15 phut
CREATE INDEX idx_order_reserved     ON sales_order(reserved_until) WHERE status = 'RESERVED';

-- order_item
CREATE INDEX idx_order_item_order   ON order_item(order_id);
CREATE INDEX idx_order_item_product ON order_item(product_id);

-- inventory
CREATE INDEX idx_inventory_product ON inventory(product_id);

-- inventory_transaction
CREATE INDEX idx_inv_tx_branch_product ON inventory_transaction(branch_id, product_id);
CREATE INDEX idx_inv_tx_created_at     ON inventory_transaction(created_at DESC);
CREATE INDEX idx_inv_tx_ref            ON inventory_transaction(ref_type, ref_id);

-- stock_document
CREATE INDEX idx_stock_doc_branch ON stock_document(branch_id);
CREATE INDEX idx_stock_doc_status ON stock_document(status);

-- outbox_event
CREATE INDEX idx_outbox_status ON outbox_event(status, created_at);

-- forecast_result
CREATE INDEX idx_forecast_branch_product ON forecast_result(branch_id, product_id, target_date);

-- audit_log
CREATE INDEX idx_audit_log_entity ON audit_log(entity, entity_id);
CREATE INDEX idx_audit_log_user   ON audit_log(user_id);

-- daily_sales (BR-18)
CREATE INDEX idx_daily_sales_branch_product ON daily_sales(branch_id, product_id, sale_date);
