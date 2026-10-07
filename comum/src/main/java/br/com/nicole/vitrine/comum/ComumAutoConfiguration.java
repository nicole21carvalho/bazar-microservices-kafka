package br.com.nicole.vitrine.comum;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.ExponentialBackOff;

import br.com.nicole.vitrine.comum.consumo.ConsumoIdempotente;
import br.com.nicole.vitrine.comum.eventos.EventoInvalidoException;
import br.com.nicole.vitrine.comum.eventos.LeitorDeEventos;
import br.com.nicole.vitrine.comum.outbox.Outbox;
import br.com.nicole.vitrine.comum.outbox.PublicadorOutbox;
import tools.jackson.databind.json.JsonMapper;

/** Tudo o que um servico da Vitrine ganha so por ter o "comum" como dependencia. */
@AutoConfiguration
@EnableScheduling
public class ComumAutoConfiguration {

    @Bean
    LeitorDeEventos leitorDeEventos(JsonMapper json) {
        return new LeitorDeEventos(json);
    }

    @Bean
    Outbox outbox(JdbcTemplate jdbc, LeitorDeEventos leitor) {
        return new Outbox(jdbc, leitor);
    }

    @Bean
    PublicadorOutbox publicadorOutbox(
            JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate transacao) {
        return new PublicadorOutbox(jdbc, kafka, transacao);
    }

    @Bean
    ConsumoIdempotente consumoIdempotente(JdbcTemplate jdbc) {
        return new ConsumoIdempotente(jdbc);
    }

    /**
     * Falha ao consumir: tenta de novo 4 vezes, com espera crescente (1 s, 2 s,
     * 4 s, 8 s), para dar tempo de um banco ou servico voltar. Se nao der, a
     * mensagem vai para o Dead Letter Topic ("<topico>-dlt") e a fila segue: uma
     * mensagem com problema nao trava as que vem depois dela.
     *
     * Evento invalido vai direto para o DLT, porque tentar de novo nao conserta
     * um JSON quebrado.
     */
    @Bean
    DefaultErrorHandler tratadorDeErrosKafka(KafkaOperations<?, ?> kafka) {
        var dlt = new DeadLetterPublishingRecoverer(
                kafka, (registro, erro) -> new TopicPartition(registro.topic() + "-dlt", registro.partition()));
        var espera = new ExponentialBackOff(1_000, 2.0);
        espera.setMaxAttempts(4);
        var tratador = new DefaultErrorHandler(dlt, espera);
        tratador.addNotRetryableExceptions(EventoInvalidoException.class);
        return tratador;
    }
}
