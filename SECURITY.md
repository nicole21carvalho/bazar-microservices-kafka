# Segurança

## Como relatar uma vulnerabilidade

**Não abra uma issue pública.** Use o relato privado do GitHub: aba **Security** do repositório → **Report a vulnerability**. Só os mantenedores veem o relato até a correção sair.

## Modelo de ameaças, em resumo

| Quem | O que pode tentar | O que impede |
|---|---|---|
| Visitante sem conta | Criar pedidos, ver pedidos alheios, derrubar a loja com requisições | Gateway nega tudo o que não foi liberado; pedidos só com token; limite por IP |
| Cliente com conta | Ver pedido de outro cliente, pagar menos, cobrar duas vezes, inundar a saga | Filtro por cliente na consulta (404 para pedido alheio); preço vem do catálogo, nunca do cliente; idempotência; limite de 10 pedidos/min |
| Alguém com o e-mail de outra pessoa | Cadastrar-se com o e-mail da vítima e enviar avisos para ela | Cadastro exige confirmar o e-mail; o serviço de pedidos recusa token sem `email_verified` |
| Quem chega à rede interna | Chamar um serviço sem passar pelo gateway | O serviço de pedidos valida o token de novo (emissor e audiência); serviços não publicam porta; usuário de banco separado por serviço |
| Dependência com falha conhecida | Explorar Tomcat, Jackson e outras | Varredura das 174 dependências (SBOM CycloneDX + osv-scanner) no CI, bloqueando o merge; versões corrigidas fixadas no `pom.xml` |

## O que está implementado

**Autenticação e autorização**
- Login e cadastro pelo **Keycloak** (OAuth2/OIDC), com **PKCE** obrigatório, verificação de e-mail, proteção contra força bruta e senha de 10 a 128 caracteres.
- Token JWT com **emissor e audiência conferidos** no gateway e de novo no serviço de pedidos; tokens de 5 minutos.
- Gateway com **negação por padrão**: rota nova só funciona depois de liberada explicitamente.
- Cliente identificado só pelas claims do token (`sub`, `email`), nunca por cabeçalho ou corpo que ele possa escrever.

**Abuso e disponibilidade**
- **Rate limiting** no gateway: 120 requisições/min por usuário (ou por IP, sem login) e 10 pedidos/min por usuário; resposta 429 com `Retry-After`.
- Corpo de no máximo 16 KB; pedido com até 20 itens e 99 unidades por item.
- Reserva de estoque com `SELECT ... FOR UPDATE` em ordem fixa: o mesmo item nunca é vendido duas vezes, e não há deadlock.

**Dados pessoais (LGPD)**
- **Minimização**: o evento que vai ao estoque não leva e-mail nem id do cliente.
- **Retenção**: eventos publicados saem do outbox após 7 dias; registros de idempotência, após 14; tópicos do Kafka guardam 7 dias (DLT, 30).
- Logs sem e-mail ou qualquer dado de cliente.

**Infraestrutura**
- Containers **sem root**, `read_only`, `cap_drop: ALL`, `no-new-privileges` e limite de memória.
- Só o gateway, o Keycloak e os painéis publicam porta, e só em `127.0.0.1`.
- **Actuator numa porta de gerência (9000) que nunca é publicada**: saúde e métricas não aparecem para fora.
- Um banco e um usuário por serviço; senhas só por variável de ambiente, validadas antes de entrar no SQL.
- Nenhum segredo no repositório: `.env` no `.gitignore` e no `.dockerignore`; o realm do Keycloak não tem usuário com senha.

## Limites conhecidos

- **Kafka sem autenticação nem TLS.** Hoje só a rede interna alcança o broker. Num ambiente compartilhado: SASL/SCRAM, ACL por serviço (cada um só escreve no próprio tópico) e TLS.
- **Rate limiting por réplica.** Os contadores ficam na memória de cada gateway; com 3 réplicas, o limite efetivo é até 3 vezes maior. Para um limite exato, o Bucket4j guarda os contadores no Redis.
- **HTTP no ambiente local.** O Keycloak e o gateway rodam sem TLS no `docker compose`. No deploy, tudo atrás de HTTPS.
- **E-mail nos eventos finais.** `PedidoConfirmado` e `PedidoCancelado` levam o e-mail, porque o serviço de notificações precisa dele. A retenção de 7 dias limita a exposição.
