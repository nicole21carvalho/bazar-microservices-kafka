package br.com.nicole.vitrine.comum.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import br.com.nicole.vitrine.comum.eventos.Evento;
import br.com.nicole.vitrine.comum.eventos.LeitorDeEventos;

/**
 * Transactional Outbox: o evento e gravado numa tabela do proprio banco, na
 * mesma transacao da mudanca de negocio, e o {@link PublicadorOutbox} envia ao
 * Kafka depois.
 *
 * Sem isto, gravar no banco e publicar no Kafka sao duas operacoes: se o
 * servico cair entre as duas, fica um pedido salvo cujo evento nunca saiu (ou
 * um evento de um pedido que nao foi salvo). Na mesma transacao, ou as duas
 * coisas acontecem, ou nenhuma.
 */
public class Outbox {

    private final JdbcTemplate jdbc;
    private final LeitorDeEventos leitor;
    private final Rastro rastro;

    public Outbox(JdbcTemplate jdbc, LeitorDeEventos leitor, Rastro rastro) {
        this.jdbc = jdbc;
        this.leitor = leitor;
        this.rastro = rastro;
    }

    public void registrar(String topico, Evento evento) {
        // Fora de uma transacao o outbox perde o sentido: o evento seria
        // gravado mesmo que a mudanca de negocio desse rollback depois.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox.registrar precisa rodar dentro de uma transacao");
        }
        jdbc.update(
                """
                INSERT INTO outbox (id, topico, chave, tipo, payload, traceparent, criado_em)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                UUID.randomUUID(),
                topico,
                evento.pedidoId().toString(),
                LeitorDeEventos.tipoDe(evento),
                leitor.paraJson(evento),
                rastro.atual(),
                Timestamp.from(Instant.now()));
    }
}
