CREATE TABLE pedido (
    id            UUID          PRIMARY KEY,
    cliente_id    VARCHAR(100)  NOT NULL,
    cliente_email VARCHAR(254)  NOT NULL,
    status        VARCHAR(30)   NOT NULL,
    total         NUMERIC(12,2),
    motivo        VARCHAR(300),
    criado_em     TIMESTAMPTZ   NOT NULL,
    atualizado_em TIMESTAMPTZ   NOT NULL,
    versao        BIGINT        NOT NULL
);
-- A listagem "meus pedidos" filtra por cliente e ordena por data.
CREATE INDEX ix_pedido_cliente ON pedido (cliente_id, criado_em DESC);

CREATE TABLE pedido_item (
    pedido_id  UUID        NOT NULL REFERENCES pedido (id),
    produto_id VARCHAR(60) NOT NULL,
    quantidade INTEGER     NOT NULL CHECK (quantidade > 0)
);
CREATE INDEX ix_pedido_item_pedido ON pedido_item (pedido_id);
