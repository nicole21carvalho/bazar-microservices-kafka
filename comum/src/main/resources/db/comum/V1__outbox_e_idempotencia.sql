-- Tabelas que todo servico tem: o outbox (eventos a publicar) e o registro
-- dos eventos ja consumidos (idempotencia). As migracoes de cada servico
-- comecam na V2.

CREATE TABLE outbox (
    id           UUID PRIMARY KEY,
    topico       VARCHAR(100) NOT NULL,
    chave        VARCHAR(100) NOT NULL,
    tipo         VARCHAR(100) NOT NULL,
    payload      JSONB        NOT NULL,
    -- Contexto do rastreamento (W3C traceparent) de quem criou o evento, para
    -- a publicacao continuar o mesmo trace. Vazio se nao havia trace.
    traceparent  VARCHAR(55),
    criado_em    TIMESTAMPTZ  NOT NULL,
    publicado_em TIMESTAMPTZ
);

-- Indice parcial: o publicador so procura o que falta publicar, e esse
-- conjunto fica pequeno mesmo com milhoes de eventos ja enviados.
CREATE INDEX ix_outbox_pendentes ON outbox (criado_em) WHERE publicado_em IS NULL;

CREATE TABLE evento_processado (
    evento_id     UUID         NOT NULL,
    consumidor    VARCHAR(100) NOT NULL,
    processado_em TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (evento_id, consumidor)
);
