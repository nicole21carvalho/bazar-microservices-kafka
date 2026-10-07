#!/bin/sh
# Roda uma vez, quando o volume do Postgres e criado. Um banco e um usuario por
# servico: o usuario do pagamentos nao consegue nem ler as tabelas do pedidos.
# Num ambiente maior, seriam instancias separadas; aqui, a mesma instancia
# economiza memoria sem perder o isolamento entre os servicos.
set -eu

criar() {
    servico="$1"
    senha="$2"
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<SQL
CREATE USER $servico WITH PASSWORD '$senha';
CREATE DATABASE $servico OWNER $servico;
REVOKE ALL ON DATABASE $servico FROM PUBLIC;
SQL
}

criar pedidos "$DB_SENHA_PEDIDOS"
criar estoque "$DB_SENHA_ESTOQUE"
criar pagamentos "$DB_SENHA_PAGAMENTOS"
criar notificacoes "$DB_SENHA_NOTIFICACOES"
