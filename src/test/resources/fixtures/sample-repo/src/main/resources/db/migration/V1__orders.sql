CREATE TABLE orders (
    id              UUID PRIMARY KEY,
    customer_email  VARCHAR(255)   NOT NULL,
    total           NUMERIC(12, 2) NOT NULL,
    refunded_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    status          VARCHAR(20)    NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL
);

CREATE INDEX idx_orders_customer_email ON orders (customer_email, created_at DESC);
