CREATE TABLE produto (
    id         VARCHAR(60)   PRIMARY KEY,
    nome       VARCHAR(120)  NOT NULL,
    preco      NUMERIC(12,2) NOT NULL CHECK (preco > 0),
    -- CHECK no banco: mesmo com um bug no codigo, o estoque nunca fica negativo.
    disponivel INTEGER       NOT NULL CHECK (disponivel >= 0)
);

CREATE TABLE reserva (
    pedido_id UUID          PRIMARY KEY,
    status    VARCHAR(20)   NOT NULL,
    total     NUMERIC(12,2) NOT NULL,
    criada_em TIMESTAMPTZ   NOT NULL
);

CREATE TABLE reserva_item (
    pedido_id  UUID        NOT NULL REFERENCES reserva (pedido_id),
    produto_id VARCHAR(60) NOT NULL REFERENCES produto (id),
    quantidade INTEGER     NOT NULL CHECK (quantidade > 0)
);
CREATE INDEX ix_reserva_item_pedido ON reserva_item (pedido_id);
