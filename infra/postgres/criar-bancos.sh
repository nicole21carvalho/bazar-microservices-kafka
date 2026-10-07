#!/bin/sh
# Roda uma vez, quando o volume do Postgres e criado. Um banco e um usuario por
# servico: o usuario do pagamentos nao consegue nem ler as tabelas do pedidos.
# Num ambiente maior, seriam instancias separadas; aqui, a mesma instancia
# economiza memoria sem perder o isolamento entre os servicos.
set -eu

criar() {
    servico="$1"
    senha="$2"
    # A senha entra no comando SQL: com aspas, ponto e virgula ou espaco, ela
    # quebraria o comando ou permitiria injetar outro. Aceita so letras e
    # numeros (o .env.example manda gerar com "openssl rand -hex 24").
    case "$senha" in
        "" | *[!A-Za-z0-9]*)
            echo "Senha de $servico vazia ou com caractere fora de [A-Za-z0-9]" >&2
            exit 1
            ;;
    esac
    if [ "${#senha}" -lt 24 ]; then
        echo "Senha de $servico com menos de 24 caracteres" >&2
        exit 1
    fi
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
criar keycloak "$DB_SENHA_KEYCLOAK"
