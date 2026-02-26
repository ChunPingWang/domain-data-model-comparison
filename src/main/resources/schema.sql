-- Approach 1 & 2: DDD tables
CREATE TABLE IF NOT EXISTS orders (
    id UUID PRIMARY KEY,
    customer_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    total_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS order_line_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id),
    product_id VARCHAR(255) NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    quantity INT NOT NULL,
    unit_price NUMERIC(19,2) NOT NULL,
    subtotal NUMERIC(19,2) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_order_line_items_order_id ON order_line_items(order_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
CREATE INDEX IF NOT EXISTS idx_order_line_items_product_id ON order_line_items(product_id);

-- Approach 3: Traditional JPA tables
CREATE TABLE IF NOT EXISTS trad_orders (
    id UUID PRIMARY KEY,
    customer_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    total_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS trad_order_line_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES trad_orders(id),
    product_id VARCHAR(255) NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    quantity INT NOT NULL,
    unit_price NUMERIC(19,2) NOT NULL,
    subtotal NUMERIC(19,2) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_trad_order_line_items_order_id ON trad_order_line_items(order_id);
CREATE INDEX IF NOT EXISTS idx_trad_orders_status ON trad_orders(status);
CREATE INDEX IF NOT EXISTS idx_trad_order_line_items_product_id ON trad_order_line_items(product_id);

-- Approach 4: Traditional JDBC tables
CREATE TABLE IF NOT EXISTS jdbc_orders (
    id UUID PRIMARY KEY,
    customer_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    total_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS jdbc_order_line_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES jdbc_orders(id),
    product_id VARCHAR(255) NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    quantity INT NOT NULL,
    unit_price NUMERIC(19,2) NOT NULL,
    subtotal NUMERIC(19,2) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_jdbc_order_line_items_order_id ON jdbc_order_line_items(order_id);
CREATE INDEX IF NOT EXISTS idx_jdbc_orders_status ON jdbc_orders(status);
CREATE INDEX IF NOT EXISTS idx_jdbc_order_line_items_product_id ON jdbc_order_line_items(product_id);
