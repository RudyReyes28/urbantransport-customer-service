CREATE TABLE customers (
                           id         UUID         PRIMARY KEY,
                           nit        VARCHAR(20)  NOT NULL,
                           dpi        VARCHAR(13),
                           full_name  VARCHAR(150) NOT NULL,
                           phone      VARCHAR(20),
                           address    VARCHAR(255),
                           email      VARCHAR(150),
                           user_id    UUID,
                           is_generic BOOLEAN      NOT NULL DEFAULT FALSE,
                           created_at TIMESTAMP    NOT NULL DEFAULT now(),
                           updated_at TIMESTAMP    NOT NULL DEFAULT now(),
                           CONSTRAINT ck_customers_nit_format CHECK (nit ~ '^[0-9A-Z]+$'),
    CONSTRAINT ck_customers_generic_without_account CHECK (NOT is_generic OR (user_id IS NULL AND dpi IS NULL))
);

CREATE UNIQUE INDEX uq_customers_nit     ON customers (nit);
CREATE UNIQUE INDEX uq_customers_dpi     ON customers (dpi)     WHERE dpi IS NOT NULL;
CREATE UNIQUE INDEX uq_customers_user_id ON customers (user_id) WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX uq_customers_generic ON customers (is_generic) WHERE is_generic;

INSERT INTO customers (id, nit, full_name, is_generic)
VALUES ('00000000-0000-0000-0000-000000000001', 'CF', 'Consumidor Final', TRUE);