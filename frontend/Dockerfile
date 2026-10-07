# Estagio 1: gera os arquivos estaticos. Node e node_modules ficam aqui.
FROM node:24-alpine AS build
WORKDIR /app
# Enderecos que o navegador usa. Vao para dentro do JavaScript e da CSP do
# nginx no build: cada ambiente (local, nuvem) gera a sua imagem.
ARG API_URL=http://localhost:8080
ARG KEYCLOAK_ORIGEM=http://localhost:8180
ENV VITE_API_URL=${API_URL} \
    VITE_KEYCLOAK_URL=${KEYCLOAK_ORIGEM}/realms/vitrine
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build \
 && sed -e "s#__API__#${API_URL}#g" -e "s#__KEYCLOAK__#${KEYCLOAK_ORIGEM}#g" nginx.conf > nginx.final.conf

# Estagio 2: so o nginx e os arquivos prontos. A variante unprivileged roda sem root.
FROM nginxinc/nginx-unprivileged:1.30-alpine
COPY --from=build /app/nginx.final.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
