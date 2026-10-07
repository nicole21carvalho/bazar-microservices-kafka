-- Registro do que foi avisado. Guarda so o pedido e o tipo do aviso: o e-mail
-- do cliente ja esta no servico de pedidos e nao precisa de outra copia.
CREATE TABLE notificacao (
    id         BIGSERIAL   PRIMARY KEY,
    pedido_id  UUID        NOT NULL,
    tipo       VARCHAR(20) NOT NULL,
    enviada_em TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_notificacao_pedido ON notificacao (pedido_id);
