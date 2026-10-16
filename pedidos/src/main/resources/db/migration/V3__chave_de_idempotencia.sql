-- Chave de idempotencia da criacao de pedido (cabecalho Idempotency-Key). Quem
-- chama pode repetir o POST depois de um timeout sem criar um segundo pedido: a
-- mesma chave, do mesmo cliente, devolve o pedido que ja foi criado.
CREATE TABLE pedido_chave (
    cliente_id VARCHAR(100)  NOT NULL,
    chave      VARCHAR(100)  NOT NULL,
    -- Os itens do primeiro pedido, ja somados e em ordem: a mesma chave com
    -- outros itens e erro de quem chamou, e nao uma repeticao.
    impressao  VARCHAR(2000) NOT NULL,
    -- Conferida so no commit: a chave e gravada antes do pedido, para que duas
    -- requisicoes com a mesma chave esperem uma pela outra na chave primaria.
    pedido_id  UUID          NOT NULL REFERENCES pedido (id) DEFERRABLE INITIALLY DEFERRED,
    criado_em  TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (cliente_id, chave)
);
-- A limpeza apaga as chaves vencidas por data.
CREATE INDEX ix_pedido_chave_criado ON pedido_chave (criado_em);
