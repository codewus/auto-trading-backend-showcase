-- New synthetic records only. Idempotent, additive fixture; no existing rows are removed.
BEGIN;
INSERT INTO legacy_trade(actor_id,symbol,exchange_order_id,side,quantity,occurred_at)
VALUES ('demo-history','DEMOUSDT','fx-history-legacy','BUY',1,'2026-01-01T00:00:01Z'),
       ('demo-history','DEMOUSDT','fx-history-both','BUY',2,'2026-01-01T00:00:02Z')
ON CONFLICT (actor_id,symbol,exchange_order_id) DO NOTHING;

INSERT INTO demo_order(actor_id,action_id,client_order_id,fingerprint,symbol,side,quantity,status,created_at)
VALUES ('demo-history','fixture-new','demo-154f4eedb30d2a642a66d71a712993','7e9e3dadc3ed3940adce60ba0d7c542a4fcee24f03ac601004536257d38005a4','DEMOUSDT','BUY',3,'CONFIRMED','2026-01-01T00:00:03Z'),
       ('demo-history','fixture-both','demo-13ed7cca49b928f467cf8bd32529e4','39ff8825cfa1c4fdb0c0b50fa178196f88e621228ff90a578cbfd813a42669c7','DEMOUSDT','BUY',2,'CONFIRMED','2026-01-01T00:00:02Z')
ON CONFLICT (actor_id,action_id) DO NOTHING;

INSERT INTO fill(order_id,actor_id,symbol,exchange_order_id,side,quantity,legacy_trade_id,filled_at)
SELECT o.id,o.actor_id,o.symbol,'fx-history-new',o.side,o.quantity,NULL,'2026-01-01T00:00:03Z'
FROM demo_order o WHERE o.actor_id='demo-history' AND o.action_id='fixture-new'
ON CONFLICT DO NOTHING;
INSERT INTO fill(order_id,actor_id,symbol,exchange_order_id,side,quantity,legacy_trade_id,filled_at)
SELECT o.id,o.actor_id,o.symbol,t.exchange_order_id,o.side,o.quantity,t.id,t.occurred_at
FROM demo_order o JOIN legacy_trade t ON t.actor_id=o.actor_id AND t.symbol=o.symbol AND t.exchange_order_id='fx-history-both'
WHERE o.actor_id='demo-history' AND o.action_id='fixture-both'
ON CONFLICT DO NOTHING;
COMMIT;
