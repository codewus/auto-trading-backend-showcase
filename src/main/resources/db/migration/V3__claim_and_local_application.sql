CREATE TABLE submit_claim (
    order_id BIGINT PRIMARY KEY,
    client_order_id VARCHAR(36) NOT NULL UNIQUE,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (order_id, client_order_id) REFERENCES demo_order (id, client_order_id)
);

CREATE TABLE local_state (
    actor_id VARCHAR(64) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    applied_quantity NUMERIC(28,8) NOT NULL DEFAULT 0,
    applied_fill_count BIGINT NOT NULL DEFAULT 0 CHECK (applied_fill_count >= 0),
    PRIMARY KEY (actor_id, symbol)
);

CREATE TABLE application_ledger (
    fill_id BIGINT PRIMARY KEY REFERENCES fill (id),
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
