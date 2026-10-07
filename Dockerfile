# syntax=docker/dockerfile:1
# Um Dockerfile para os quatro servicos: SERVICO escolhe qual imagem gerar.
#   docker build --build-arg SERVICO=pedidos -t vitrine-pedidos .

FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICO
WORKDIR /app
# O projeto inteiro, e nao so o servico: o pom pai lista todos os modulos, e o
# Maven exige que as pastas existam. O .dockerignore deixa target/ e .env fora.
COPY . .
# Cache do ~/.m2 entre builds: o segundo servico nao baixa tudo de novo.
# -pl/-am: compila so o servico pedido e o "comum" de que ele depende.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl ${SERVICO} -am package -DskipTests
# Separa o JAR em camadas: dependencias mudam pouco e ficam numa camada
# propria, entao uma mudanca no codigo so reenvia a camada pequena.
RUN java -Djarmode=tools -jar ${SERVICO}/target/${SERVICO}-*.jar extract --layers --launcher --destination extraido

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Usuario sem privilegio: se alguem escapar da aplicacao, nao e root.
RUN addgroup -S vitrine && adduser -S vitrine -G vitrine
COPY --from=build /app/extraido/dependencies/ ./
COPY --from=build /app/extraido/spring-boot-loader/ ./
COPY --from=build /app/extraido/snapshot-dependencies/ ./
COPY --from=build /app/extraido/application/ ./
USER vitrine
# 75% da memoria do container para o heap: o limite vem do compose ou do
# Kubernetes, e a JVM se ajusta a ele em vez de supor a memoria da maquina.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
