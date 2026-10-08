#!/usr/bin/env bash
# Prepara a VM na primeira inicializacao (o cloud-init roda este script uma vez).
# Log completo: /var/log/sagaz-preparo.log
#
# Depois daqui, quem faz o deploy e o Flux, de dentro do cluster: ele le a
# pasta infra/ do repositorio a cada 5 minutos e aplica o que mudou (GitOps).
# Por isso nenhuma porta de administracao fica aberta para a internet, e o CI
# nao guarda nenhuma credencial do cluster.
set -euo pipefail
exec > >(tee -a /var/log/sagaz-preparo.log) 2>&1

# Gravado pelo cloud-init: VERSAO_K3S, VERSAO_FLUX, REPOSITORIO, RAMO.
# shellcheck source=/dev/null
source /opt/sagaz/config

passo() { echo "== $(date -u +%FT%TZ) $*"; }

esperar() { # esperar <descricao> <comando...>: tenta por ate 15 minutos
    local descricao=$1; shift
    for _ in $(seq 180); do
        if "$@" >/dev/null 2>&1; then return 0; fi
        sleep 5
    done
    echo "Tempo esgotado: $descricao" >&2
    return 1
}

passo "Firewall da VM"
# As imagens Ubuntu da Oracle vem com iptables recusando tudo menos SSH.
# Libera HTTP/HTTPS e o trafego interno do k3s (rede dos pods e dos services).
# A porta da API do Kubernetes (6443) continua fechada para fora.
iptables -I INPUT 1 -p tcp -m multiport --dports 80,443 -j ACCEPT
iptables -I INPUT 1 -s 10.42.0.0/16 -j ACCEPT
iptables -I INPUT 1 -s 10.43.0.0/16 -j ACCEPT
netfilter-persistent save

passo "k3s $VERSAO_K3S"
# Instalador da mesma versao fixada (e nao o get.k3s.io, que muda sozinho);
# ele confere o hash do binario baixado. --secrets-encryption cifra os Secrets
# no banco do cluster.
curl -sfL "https://raw.githubusercontent.com/k3s-io/k3s/$VERSAO_K3S/install.sh" -o /tmp/instalar-k3s.sh
INSTALL_K3S_VERSION="$VERSAO_K3S" sh /tmp/instalar-k3s.sh server --secrets-encryption
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
esperar "no pronto" sh -c 'kubectl get nodes | grep -qw Ready'

passo "cert-manager e emissor do Let's Encrypt"
# O chart vem da pasta de manifestos do k3s; o emissor so pode ser criado
# depois que o webhook do cert-manager responde.
esperar "cert-manager" kubectl -n cert-manager rollout status deploy/cert-manager-webhook --timeout=10s
esperar "emissor" kubectl apply -f /opt/sagaz/cluster-issuer.yaml

passo "Segredos da Sagaz"
# Senhas aleatorias geradas aqui mesmo: nao passam pelo Terraform, pelo CI
# nem pelo repositorio. Para ler uma delas (por SSH):
#   sudo kubectl -n sagaz get secret sagaz-segredos -o jsonpath='{.data.KC_SENHA_ADMIN}' | base64 -d
kubectl create namespace sagaz --dry-run=client -o yaml | kubectl apply -f -
if ! kubectl -n sagaz get secret sagaz-segredos >/dev/null 2>&1; then
    senha() { openssl rand -hex 24; }
    kubectl -n sagaz create secret generic sagaz-segredos \
        --from-literal=DB_SENHA_ADMIN="$(senha)" \
        --from-literal=DB_SENHA_PEDIDOS="$(senha)" \
        --from-literal=DB_SENHA_ESTOQUE="$(senha)" \
        --from-literal=DB_SENHA_PAGAMENTOS="$(senha)" \
        --from-literal=DB_SENHA_NOTIFICACOES="$(senha)" \
        --from-literal=DB_SENHA_KEYCLOAK="$(senha)" \
        --from-literal=KC_SENHA_ADMIN="$(senha)"
fi

passo "Flux $VERSAO_FLUX (GitOps)"
kubectl apply -f "https://github.com/fluxcd/flux2/releases/download/$VERSAO_FLUX/install.yaml"
esperar "Flux" kubectl -n flux-system rollout status deploy/kustomize-controller --timeout=10s
kubectl apply -f - <<EOF
apiVersion: source.toolkit.fluxcd.io/v1
kind: GitRepository
metadata:
  name: sagaz
  namespace: flux-system
spec:
  interval: 5m
  url: $REPOSITORIO
  ref:
    branch: $RAMO
  # So a pasta infra/ interessa ao cluster.
  ignore: |
    /*
    !/infra/
---
apiVersion: kustomize.toolkit.fluxcd.io/v1
kind: Kustomization
metadata:
  name: sagaz
  namespace: flux-system
spec:
  interval: 10m
  sourceRef:
    kind: GitRepository
    name: sagaz
  path: ./infra
  # O que sair do repositorio sai do cluster tambem.
  prune: true
  wait: true
  timeout: 15m
EOF

passo "Pronto. Acompanhe com: sudo kubectl -n flux-system get kustomizations -w"
