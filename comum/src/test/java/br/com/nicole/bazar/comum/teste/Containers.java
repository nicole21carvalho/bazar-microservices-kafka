package br.com.nicole.bazar.comum.teste;

import java.time.Duration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Kafka e Postgres de verdade, nas mesmas versoes do docker-compose. O
 * SELECT ... FOR UPDATE, o ON CONFLICT e o jsonb so podem ser testados num
 * Postgres real; um banco em memoria passaria nos testes e falharia em producao.
 */
@TestConfiguration(proxyBeanMethods = false)
public class Containers {

    // O padrao (60 s) falha numa maquina ocupada ou num runner de CI lento, e
    // o teste quebraria sem nenhum defeito no codigo.
    private static final Duration ESPERA = Duration.ofMinutes(3);

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        // Por padrao o Kafka espera 3 s antes de distribuir as particoes a um
        // grupo de consumidores novo; cada leitura dos testes cria um grupo.
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
                .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
                .withStartupTimeout(ESPERA);
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine")).withStartupTimeout(ESPERA);
    }
}
