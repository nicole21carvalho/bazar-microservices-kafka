package br.com.nicole.vitrine.comum.outbox;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.nicole.vitrine.comum.eventos.Cabecalhos;

/**
 * Le o outbox e publica no Kafka. Garante entrega "pelo menos uma vez": se o
 * servico cair depois de publicar e antes de marcar como publicado, o evento sai
 * de novo na proxima rodada. Por isso todo consumidor e idempotente
 * ({@link br.com.nicole.vitrine.comum.consumo.ConsumoIdempotente}).
 */
public class PublicadorOutbox {

    private static final Logger log = LoggerFactory.getLogger(PublicadorOutbox.class);
    private static final int LOTE = 100;

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transacao;
    private final Rastro rastro;

    public PublicadorOutbox(
            JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate transacao, Rastro rastro) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transacao = transacao;
        this.rastro = rastro;
    }

    private record Pendente(UUID id, String topico, String chave, String tipo, String payload, String traceparent) {}

    @Scheduled(fixedDelayString = "${vitrine.outbox.intervalo:500ms}")
    public void publicarPendentes() {
        try {
            transacao.executeWithoutResult(status -> publicarLote());
        } catch (RuntimeException e) {
            // A transacao voltou atras: nada foi marcado como publicado e o lote
            // inteiro sai de novo na proxima rodada, na mesma ordem.
            log.warn("Outbox: falha ao publicar, nova tentativa em seguida: {}", e.getMessage());
        }
    }

    private void publicarLote() {
        // SKIP LOCKED: com varias replicas do servico, cada uma pega linhas
        // diferentes em vez de esperar a outra ou publicar em dobro.
        List<Pendente> pendentes = jdbc.query(
                """
                SELECT id, topico, chave, tipo, payload::text, traceparent FROM outbox
                WHERE publicado_em IS NULL
                ORDER BY criado_em
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (rs, i) -> new Pendente(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)),
                LOTE);

        for (Pendente p : pendentes) {
            ProducerRecord<String, String> registro = new ProducerRecord<>(p.topico(), p.chave(), p.payload());
            // O id da linha do outbox vira o id do evento: um reenvio leva o
            // mesmo id, e o consumidor reconhece a repeticao.
            registro.headers().add(Cabecalhos.ID, p.id().toString().getBytes(StandardCharsets.UTF_8));
            registro.headers().add(Cabecalhos.TIPO, p.tipo().getBytes(StandardCharsets.UTF_8));
            // Publica dentro do trace de quem criou o evento: o KafkaTemplate poe o
            // traceparent no cabecalho, e o consumidor continua o mesmo trace.
            rastro.continuar(p.traceparent(), "outbox publicar " + p.tipo(), () -> {
                try {
                    // Espera a confirmacao do Kafka antes de marcar como publicado.
                    return kafka.send(registro).get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("Kafka nao confirmou o evento " + p.id(), e);
                }
            });
            jdbc.update("UPDATE outbox SET publicado_em = now() WHERE id = ?", p.id());
        }
    }
}
