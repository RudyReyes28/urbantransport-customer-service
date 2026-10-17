CREATE TABLE wallets (
                         customer_id UUID          PRIMARY KEY REFERENCES customers (id),
                         balance     NUMERIC(12,2) NOT NULL DEFAULT 0,
                         version     BIGINT        NOT NULL DEFAULT 0,
                         updated_at  TIMESTAMP     NOT NULL DEFAULT now(),
                         CONSTRAINT ck_wallets_balance_non_negative CHECK (balance >= 0)
);

CREATE TABLE wallet_transactions (
                                     id             UUID          PRIMARY KEY,
                                     wallet_id      UUID          NOT NULL REFERENCES wallets (customer_id),
                                     type           VARCHAR(10)   NOT NULL,
                                     amount         NUMERIC(12,2) NOT NULL,
                                     balance_after  NUMERIC(12,2) NOT NULL,
                                     payment_date   DATE          NOT NULL,
                                     reference_type VARCHAR(30),
                                     reference_id   UUID,
                                     created_at     TIMESTAMP     NOT NULL DEFAULT now(),
                                     CONSTRAINT ck_wallet_tx_type   CHECK (type IN ('RECHARGE', 'DEBIT', 'REFUND')),
                                     CONSTRAINT ck_wallet_tx_amount CHECK (amount > 0)
);

-- Idempotencia del dinero: un mismo pago o reembolso no se aplica dos veces.
-- Las recargas manuales no llevan referencia (NULL) y por eso no chocan entre sí.
CREATE UNIQUE INDEX uq_wallet_tx_reference ON wallet_transactions (reference_type, reference_id, type);
CREATE INDEX ix_wallet_tx_wallet_created ON wallet_transactions (wallet_id, created_at DESC);