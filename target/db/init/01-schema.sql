-- NightShift target system schema. Runs once when the Postgres volume is created
-- (docker-entrypoint-initdb.d) and in every Testcontainers database.

CREATE SCHEMA ops;
CREATE SCHEMA orders;
CREATE SCHEMA payments;
CREATE SCHEMA inventory;

-- ops: runtime config/flags with history, deploy registry, fault switches -------------------
CREATE TABLE ops.settings (
    service    text        NOT NULL,
    kind       text        NOT NULL CHECK (kind IN ('CONFIG', 'FLAG')),
    key        text        NOT NULL,
    value      text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (service, kind, key)
);

CREATE TABLE ops.settings_history (
    id         bigserial PRIMARY KEY,
    service    text        NOT NULL,
    kind       text        NOT NULL,
    key        text        NOT NULL,
    old_value  text,
    new_value  text        NOT NULL,
    changed_by text        NOT NULL,
    reason     text,
    changed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX settings_history_lookup ON ops.settings_history (service, kind, changed_at DESC);

CREATE TABLE ops.deploys (
    id               bigserial PRIMARY KEY,
    service          text        NOT NULL,
    version          text        NOT NULL,
    previous_version text,
    changelog        text,
    initiated_by     text        NOT NULL,
    status           text        NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
    deployed_at      timestamptz NOT NULL DEFAULT now(),
    completed_at     timestamptz
);
CREATE INDEX deploys_service_time ON ops.deploys (service, deployed_at DESC);

CREATE TABLE ops.service_starts (
    id         bigserial PRIMARY KEY,
    service    text        NOT NULL,
    version    text        NOT NULL,
    instance   text        NOT NULL,
    started_at timestamptz NOT NULL
);
CREATE INDEX service_starts_service_time ON ops.service_starts (service, started_at DESC);

CREATE TABLE ops.fault_switches (
    service    text        NOT NULL,
    name       text        NOT NULL,
    value      text        NOT NULL,
    scenario   text,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (service, name)
);

-- orders --------------------------------------------------------------------------------------
CREATE TABLE orders.customers (
    id           bigint PRIMARY KEY,
    name         text NOT NULL,
    loyalty_tier text CHECK (loyalty_tier IN ('BRONZE', 'SILVER', 'GOLD'))  -- NULL = no tier
);

CREATE TABLE orders.price_list (
    sku         text PRIMARY KEY,
    name        text NOT NULL,
    price_cents int  NOT NULL CHECK (price_cents > 0)
);

CREATE TABLE orders.orders (
    id            bigserial PRIMARY KEY,
    customer_id   bigint      NOT NULL REFERENCES orders.customers (id),
    status        text        NOT NULL CHECK (status IN ('PENDING', 'PAID', 'FAILED')),
    total_cents   bigint      NOT NULL,
    customer_note text,
    failure       text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

-- Deliberately no index on sku: the new_pricing code path aggregates over this table.
CREATE TABLE orders.order_lines (
    id               bigserial PRIMARY KEY,
    order_id         bigint      NOT NULL,
    sku              text        NOT NULL,
    qty              int         NOT NULL CHECK (qty > 0),
    unit_price_cents int         NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX order_lines_order ON orders.order_lines (order_id);

-- payments ------------------------------------------------------------------------------------
CREATE TABLE payments.charges (
    id             bigserial PRIMARY KEY,
    order_id       bigint      NOT NULL,
    customer_id    bigint      NOT NULL,
    amount_cents   bigint      NOT NULL,
    discount_cents bigint      NOT NULL DEFAULT 0,
    status         text        NOT NULL CHECK (status IN ('CAPTURED', 'DECLINED')),
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX charges_order ON payments.charges (order_id);

-- inventory -----------------------------------------------------------------------------------
CREATE TABLE inventory.stock (
    sku        text PRIMARY KEY,
    available  int         NOT NULL CHECK (available >= 0),
    reserved   int         NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE inventory.reservations (
    id         bigserial PRIMARY KEY,
    order_id   bigint      NOT NULL,
    sku        text        NOT NULL,
    qty        int         NOT NULL CHECK (qty > 0),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX reservations_order ON inventory.reservations (order_id);
