"""Teste de ponta a ponta com o sistema inteiro rodando (docker compose up -d).

Keycloak -> gateway -> 4 servicos -> Kafka -> Postgres -> Mailpit, mais o trace
no Jaeger. Cria usuarios e um cliente OAuth temporarios (senhas aleatorias)
pela API de administracao do Keycloak e apaga tudo no final: nenhuma credencial
fica no repositorio.

    pip install httpx
    python scripts/teste_ponta_a_ponta.py
"""

import collections
import datetime
import json
import pathlib
import secrets
import sys
import time

import httpx

GATEWAY = "http://localhost:8080"
KEYCLOAK = "http://localhost:8180"
MAILPIT = "http://localhost:8025"
JAEGER = "http://localhost:16686"
ESPERA = httpx.Timeout(60.0)

raiz = pathlib.Path(__file__).resolve().parents[1]
ENV = dict(
    linha.split("=", 1)
    for linha in (raiz / ".env").read_text(encoding="utf-8").split()
    if "=" in linha and not linha.startswith("#")
)
falhas = 0


def conferir(nome, ok, detalhe=""):
    global falhas
    falhas += 0 if ok else 1
    print(f"{'OK   ' if ok else 'FALHA'} {nome} {detalhe}")


def servicos_por_trace(servico="gateway", minutos=15):
    """Jaeger 2 (API v3, formato OTLP): para cada trace, os servicos por onde passou."""
    agora = datetime.datetime.now(datetime.timezone.utc)
    params = {
        "query.service_name": servico,
        "query.start_time_min": (agora - datetime.timedelta(minutes=minutos)).isoformat().replace("+00:00", "Z"),
        "query.start_time_max": agora.isoformat().replace("+00:00", "Z"),
        "query.search_depth": 100,
    }
    resposta = httpx.get(f"{JAEGER}/api/v3/traces", params=params, timeout=ESPERA)
    resposta.raise_for_status()
    servicos = collections.defaultdict(set)
    for linha in resposta.text.splitlines():
        if not linha.strip():
            continue
        for rs in json.loads(linha).get("result", {}).get("resourceSpans", []):
            nome = next(a["value"]["stringValue"] for a in rs["resource"]["attributes"] if a["key"] == "service.name")
            for ss in rs.get("scopeSpans", []):
                for span in ss.get("spans", []):
                    servicos[span["traceId"]].add(nome)
    return servicos


# --- Preparacao no Keycloak, como admin ---
admin = httpx.post(f"{KEYCLOAK}/realms/master/protocol/openid-connect/token", timeout=ESPERA, data={
    "grant_type": "password", "client_id": "admin-cli", "username": "admin", "password": ENV["KC_SENHA_ADMIN"],
}).raise_for_status().json()["access_token"]
adm = httpx.Client(base_url=f"{KEYCLOAK}/admin/realms/vitrine", timeout=ESPERA,
                   headers={"Authorization": f"Bearer {admin}"})

senha = secrets.token_urlsafe(16)


def criar_usuario(email, verificado):
    adm.post("/users", json={
        "username": email, "email": email, "emailVerified": verificado, "enabled": True,
        "firstName": "Teste", "lastName": "E2E",
        "credentials": [{"type": "password", "value": senha, "temporary": False}],
    }).raise_for_status()
    return adm.get("/users", params={"email": email, "exact": "true"}).json()[0]["id"]


email = f"teste-{secrets.token_hex(3)}@exemplo.com"
email_nao_verificado = f"nao-verificado-{secrets.token_hex(3)}@exemplo.com"
usuarios = [criar_usuario(email, True), criar_usuario(email_nao_verificado, False)]

cliente_id = f"teste-e2e-{secrets.token_hex(3)}"
segredo = secrets.token_urlsafe(24)
adm.post("/clients", json={
    "clientId": cliente_id, "secret": segredo, "publicClient": False,
    "directAccessGrantsEnabled": True, "standardFlowEnabled": False,
    "protocolMappers": [{"name": "aud", "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
                         "config": {"included.custom.audience": "vitrine-api", "access.token.claim": "true"}}],
}).raise_for_status()
cliente_uuid = adm.get("/clients", params={"clientId": cliente_id}).json()[0]["id"]


def pedir_token(usuario):
    return httpx.post(f"{KEYCLOAK}/realms/vitrine/protocol/openid-connect/token", timeout=ESPERA, data={
        "grant_type": "password", "client_id": cliente_id, "client_secret": segredo,
        "username": usuario, "password": senha, "scope": "openid email",
    })


try:
    cab = {"Authorization": f"Bearer {pedir_token(email).raise_for_status().json()['access_token']}"}
    http = httpx.Client(base_url=GATEWAY, timeout=ESPERA)

    # --- Seguranca ---
    conferir("catalogo abre sem login", http.get("/api/produtos").status_code == 200)
    conferir("pedidos sem token -> 401", http.get("/api/pedidos").status_code == 401)
    conferir("token forjado -> 401",
             http.get("/api/pedidos", headers={"Authorization": "Bearer abc.def.ghi"}).status_code == 401)
    conferir("rota nao liberada -> 401/403", http.get("/actuator/env", headers=cab).status_code in (401, 403))
    conferir("metricas fora da porta publica", http.get("/actuator/prometheus").status_code != 200)
    r = pedir_token(email_nao_verificado)
    conferir("e-mail nao confirmado: Keycloak nem emite token", r.status_code == 400,
             r.json().get("error_description", ""))

    def estoque():
        return {p["id"]: p["disponivel"] for p in http.get("/api/produtos").json()}

    def pedir(itens):
        r = http.post("/api/pedidos", headers=cab, json={"itens": itens})
        assert r.status_code == 202, r.text
        return r.json()["id"]

    def esperar_fim(pedido_id, limite=90):
        inicio = time.time()
        while time.time() - inicio < limite:
            p = http.get(f"/api/pedidos/{pedido_id}", headers=cab).json()
            if p["status"] in ("CONFIRMADO", "CANCELADO"):
                return p, time.time() - inicio
            time.sleep(0.5)
        raise TimeoutError(pedido_id)

    # --- Saga ---
    antes = estoque()
    r1, t1 = esperar_fim(pedir([{"produtoId": "cafe-especial", "quantidade": 2},
                                {"produtoId": "caneca-ceramica", "quantidade": 1}]))
    conferir("pedido normal confirmado", r1["status"] == "CONFIRMADO", f"total={r1['total']} em {t1:.1f}s")
    conferir("estoque baixou", antes["cafe-especial"] - estoque()["cafe-especial"] == 2)

    monitor_antes = estoque()["monitor-27"]
    r2, _ = esperar_fim(pedir([{"produtoId": "monitor-27", "quantidade": 1}]))
    conferir("acima do limite cancelado", r2["status"] == "CANCELADO", f"'{r2['motivo']}'")
    time.sleep(3)
    conferir("compensacao devolveu o monitor", estoque()["monitor-27"] == monitor_antes)

    r3, _ = esperar_fim(pedir([{"produtoId": "luminaria-mesa", "quantidade": 50}]))
    conferir("sem estoque cancelado", r3["status"] == "CANCELADO")
    conferir("lista so os pedidos do proprio cliente", len(http.get("/api/pedidos", headers=cab).json()) == 3)

    time.sleep(3)
    msgs = httpx.get(f"{MAILPIT}/api/v1/search", params={"query": f"to:{email}"}, timeout=ESPERA).json()["messages"]
    conferir("3 e-mails para o cliente", len(msgs) == 3, str(sorted(m["Subject"] for m in msgs)))

    # --- Rate limiting: rajada de pedidos, sem esperar a saga entre eles ---
    rajada = [http.post("/api/pedidos", headers=cab,
                        json={"itens": [{"produtoId": "caneca-ceramica", "quantidade": 1}]}).status_code
              for _ in range(15)]
    conferir("rajada de pedidos barrada com 429", 429 in rajada and rajada[0] == 202, str(rajada))

    # --- Rastreamento: um trace atravessa os 5 servicos ---
    time.sleep(10)
    maior = max(servicos_por_trace().values(), key=len, default=set())
    conferir("um trace passa por gateway, pedidos, estoque, pagamentos e notificacoes",
             len(maior) >= 5, str(sorted(maior)))
finally:
    adm.delete(f"/clients/{cliente_uuid}")
    for u in usuarios:
        adm.delete(f"/users/{u}")
    print("(usuarios e cliente temporarios apagados do Keycloak)")

print(f"\n{'TUDO CERTO' if falhas == 0 else f'{falhas} FALHA(S)'}")
sys.exit(1 if falhas else 0)
