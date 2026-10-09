-- Checkpoint atomico: pedidos, reservas, lotes y recepciones se confirman juntos.
CREATE TABLE IF NOT EXISTS simulation_checkpoint (
    id INTEGER PRIMARY KEY CHECK (id=1),
    format_version INTEGER NOT NULL CHECK (format_version=3),
    payload BYTEA NOT NULL,
    projection JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
-- Vistas relacionales para observar la corrida sin deserializar Java.
CREATE OR REPLACE VIEW inventory_products AS
SELECT (p->>'productId')::integer AS product_id, p->>'name' AS product_name,
       (p->>'stock')::integer AS stock, (p->>'reserved')::integer AS reserved,
       (p->>'available')::integer AS available
FROM simulation_checkpoint, jsonb_array_elements(projection->'warehouse'->'stocks') p;
CREATE OR REPLACE VIEW warehouse_locations AS
SELECT (c->>'id')::integer AS cell_id, c->>'label' AS location,
       (c->>'capacity')::integer AS capacity, (c->>'used')::integer AS used, c->'contents' AS contents
FROM simulation_checkpoint, jsonb_array_elements(projection->'warehouse'->'cells') c;
CREATE OR REPLACE VIEW receipt_events AS
SELECT (r->>'id')::bigint AS receipt_id, (r->>'productId')::integer AS product_id,
       (r->>'units')::integer AS units, r->>'state' AS state, (r->>'lotId')::bigint AS lot_id
FROM simulation_checkpoint, jsonb_array_elements(projection->'receipts') r;
CREATE OR REPLACE VIEW warehouse_lots AS
SELECT (l->>'id')::bigint AS lot_id, (l->>'receiptId')::bigint AS receipt_id,
       (l->>'productId')::integer AS product_id, (l->>'receivedUnits')::integer AS received_units,
       l->'locations' AS locations
FROM simulation_checkpoint, jsonb_array_elements(projection->'warehouse'->'lots') l;
CREATE OR REPLACE VIEW distribution_orders AS
SELECT (o->>'id')::bigint AS order_id, (o->>'level')::integer AS service_level,
       (o->>'units')::integer AS total_units, o->>'state' AS state, o->>'stage' AS stage, o->'lines' AS items
FROM simulation_checkpoint, jsonb_array_elements(projection->'orders') o;
-- Cuentas de cliente; el servicio del pedido no se recalcula desde esta vista.
CREATE OR REPLACE VIEW customer_accounts AS
SELECT (c->>'id')::bigint AS client_id, c->>'name' AS client_name,
       c->>'email' AS email, (c->>'level')::integer AS service_level,
       (c->>'orders')::bigint AS total_orders, (c->>'completed')::bigint AS completed_orders
FROM simulation_checkpoint, jsonb_array_elements(projection->'clients') c;
