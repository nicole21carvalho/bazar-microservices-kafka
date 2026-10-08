# syntax=docker/dockerfile:1
# Um Dockerfile para os cinco servicos: SERVICO escolhe qual imagem gerar.
#   docker build --build-arg SERVICO=pedidos -t vitrine-pedidos .

# --platform=$BUILDPLATFORM: a compilacao roda na arquitetura de quem esta
# construindo, sempre nativa. Bytecode Java e o mesmo em x86 e ARM, entao so a
# etapa final muda por arquitetura, e ela nao executa nada (so COPY): a imagem
# ARM para a nuvem da Oracle sai sem emular processador, em segundos.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
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
COPY --from=build /app/extraido/dependencies/ ./
COPY --from=build /app/extraido/spring-boot-loader/ ./
COPY --from=build /app/extraido/snapshot-dependencies/ ./
COPY --from=build /app/extraido/application/ ./
# Usuario sem privilegio, por numero: se alguem escapar da aplicacao, nao e
# root. Numerico tambem deixa o Kubernetes conferir "runAsNonRoot" sem
# precisar abrir a imagem.
USER 1001:1001
# 75% da memoria do container para o heap: o limite vem do compose ou do
# Kubernetes, e a JVM se ajusta a ele em vez de supor a memoria da maquina.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
