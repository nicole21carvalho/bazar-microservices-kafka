CREATE TABLE pagamento (
    id        UUID          PRIMARY KEY,
    pedido_id UUID          NOT NULL UNIQUE,
    valor     NUMERIC(12,2) NOT NULL,
    status    VARCHAR(20)   NOT NULL,
    motivo    VARCHAR(300),
    criado_em TIMESTAMPTZ   NOT NULL
);
