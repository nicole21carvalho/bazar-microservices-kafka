package br.com.nicole.vitrine.comum;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
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
import br.com.nicole.vitrine.comum.outbox.LimpezaDeDados;
import br.com.nicole.vitrine.comum.outbox.Outbox;
import br.com.nicole.vitrine.comum.outbox.PublicadorOutbox;
import br.com.nicole.vitrine.comum.outbox.Rastro;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import tools.jackson.databind.json.JsonMapper;

/** Tudo o que um servico da Vitrine ganha so por ter o "comum" como dependencia. */
@AutoConfiguration
@EnableScheduling
// Padroes de rastreamento e Kafka para todos os servicos. Ficam abaixo do
// application.yml de cada um, que pode sobrescrever qualquer valor.
@PropertySource("classpath:vitrine-comum.properties")
public class ComumAutoConfiguration {

    @Bean
    LeitorDeEventos leitorDeEventos(JsonMapper json) {
        return new LeitorDeEventos(json);
    }

    @Bean
    Rastro rastro(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagador) {
        return new Rastro(tracer, propagador);
    }

    @Bean
    Outbox outbox(JdbcTemplate jdbc, LeitorDeEventos leitor, Rastro rastro) {
        return new Outbox(jdbc, leitor, rastro);
    }

    @Bean
    PublicadorOutbox publicadorOutbox(
            JdbcTemplate jdbc,
            KafkaTemplate<String, String> kafka,
            TransactionTemplate transacao,
            Rastro rastro,
            ObjectProvider<MeterRegistry> metricas) {
        // Quantos eventos esperam para sair. Se so cresce, o Kafka esta fora do
        // ar ou lento: o alerta pega antes de o cliente reclamar.
        metricas.ifAvailable(registro -> Gauge.builder("vitrine.outbox.pendentes",
                        () -> jdbc.queryForObject("SELECT count(*) FROM outbox WHERE publicado_em IS NULL", Long.class))
                .description("Eventos gravados no outbox e ainda nao publicados no Kafka")
                .register(registro));
        return new PublicadorOutbox(jdbc, kafka, transacao, rastro);
    }

    @Bean
    LimpezaDeDados limpezaDeDados(
            JdbcTemplate jdbc,
            @Value("${vitrine.retencao.outbox-dias:7}") int diasOutbox,
            @Value("${vitrine.retencao.idempotencia-dias:14}") int diasIdempotencia) {
        return new LimpezaDeDados(jdbc, diasOutbox, diasIdempotencia);
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
    DefaultErrorHandler tratadorDeErrosKafka(KafkaOperations<?, ?> kafka, ObjectProvider<MeterRegistry> metricas) {
        var dlt = new DeadLetterPublishingRecoverer(
                kafka, (registro, erro) -> new TopicPartition(registro.topic() + "-dlt", registro.partition()));
        var espera = new ExponentialBackOff(1_000, 2.0);
        espera.setMaxAttempts(4);
        var tratador = new DefaultErrorHandler((registro, erro) -> {
            // Toda mensagem no DLT precisa de alguem olhando: a metrica alimenta o alerta.
            metricas.ifAvailable(m -> Counter.builder("vitrine.mensagens.dlt")
                    .description("Mensagens que esgotaram as tentativas e foram para o Dead Letter Topic")
                    .tag("topico", registro.topic())
                    .register(m)
                    .increment());
            dlt.accept(registro, erro);
        }, espera);
        tratador.addNotRetryableExceptions(EventoInvalidoException.class);
        return tratador;
    }
}
