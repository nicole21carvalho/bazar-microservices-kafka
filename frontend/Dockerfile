# Estagio 1: gera os arquivos estaticos. Node e node_modules ficam aqui.
# Roda na arquitetura de quem constroi: HTML, CSS e JS sao iguais em x86 e ARM.
FROM --platform=$BUILDPLATFORM node:25-alpine AS build
WORKDIR /app
# Enderecos da API e do Keycloak que o navegador usa. Vazios (o padrao da
# imagem publicada pelo CI), o painel chama /api e /auth no proprio endereco
# onde foi aberto: a mesma imagem serve em qualquer dominio. O compose local
# passa os enderecos com porta (localhost:8080 e localhost:8180).
ARG API_URL=
ARG KEYCLOAK_URL=
ENV VITE_API_URL=${API_URL} \
    VITE_KEYCLOAK_URL=${KEYCLOAK_URL}
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# A CSP precisa da origem (esquema + host + porta), e nao do caminho do realm.
RUN npm run build \
 && ORIGEM_KC=$(echo "${KEYCLOAK_URL}" | sed -E 's#^(https?://[^/]+).*#\1#') \
 && sed -e "s#__API__#${API_URL}#g" -e "s#__KEYCLOAK__#${ORIGEM_KC}#g" nginx.conf > nginx.final.conf

# Estagio 2: so o nginx e os arquivos prontos. A variante unprivileged roda sem root.
FROM nginxinc/nginx-unprivileged:1.31-alpine
COPY --from=build /app/nginx.final.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
