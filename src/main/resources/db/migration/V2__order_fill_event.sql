-- Physical name demo_order avoids the SQL reserved word ORDER.
CREATE TABLE demo_order (
    id BIGSERIAL PRIMARY KEY,
    actor_id VARCHAR(64) NOT NULL,
    action_id VARCHAR(64) NOT NULL,
    client_order_id VARCHAR(36) NOT NULL UNIQUE,
    fingerprint CHAR(64) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    side VARCHAR(4) NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity NUMERIC(20,8) NOT NULL CHECK (quantity > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('INTENDED', 'UNCERTAIN', 'CONFIRMED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (actor_id, action_id),
    UNIQUE (id, actor_id, symbol),
    UNIQUE (id, client_order_id)
);

-- This Fill represents one full aggregate result for the restricted demo contract.
CREATE TABLE fill (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL UNIQUE,
    actor_id VARCHAR(64) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    exchange_order_id VARCHAR(64) NOT NULL,
    side VARCHAR(4) NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity NUMERIC(20,8) NOT NULL CHECK (quantity > 0),
    legacy_trade_id BIGINT,
    filled_at TIMESTAMPTZ NOT NULL,
    UNIQUE (actor_id, symbol, exchange_order_id),
    FOREIGN KEY (order_id, actor_id, symbol) REFERENCES demo_order (id, actor_id, symbol),
    FOREIGN KEY (legacy_trade_id, actor_id, symbol, exchange_order_id)
        REFERENCES legacy_trade (id, actor_id, symbol, exchange_order_id)
);

CREATE TABLE event (
    id BIGSERIAL PRIMARY KEY,
    fill_id BIGINT NOT NULL REFERENCES fill (id),
    type VARCHAR(32) NOT NULL CHECK (type IN ('ORDER_CONFIRMED', 'FILL_RECORDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (fill_id, type)
);
CREATE INDEX fill_actor_time_idx ON fill (actor_id, filled_at DESC, id DESC);
CREATE INDEX legacy_actor_time_idx ON legacy_trade (actor_id, occurred_at DESC, id DESC);
