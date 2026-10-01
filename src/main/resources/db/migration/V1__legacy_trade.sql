CREATE TABLE legacy_trade (
    id BIGSERIAL PRIMARY KEY,
    actor_id VARCHAR(64) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    exchange_order_id VARCHAR(64) NOT NULL,
    side VARCHAR(4) NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity NUMERIC(20,8) NOT NULL CHECK (quantity > 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    UNIQUE (actor_id, symbol, exchange_order_id),
    UNIQUE (id, actor_id, symbol, exchange_order_id)
);
