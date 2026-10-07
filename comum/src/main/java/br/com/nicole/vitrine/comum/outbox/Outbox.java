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

    public Outbox(JdbcTemplate jdbc, LeitorDeEventos leitor) {
        this.jdbc = jdbc;
        this.leitor = leitor;
    }

    public void registrar(String topico, Evento evento) {
        // Fora de uma transacao o outbox perde o sentido: o evento seria
        // gravado mesmo que a mudanca de negocio desse rollback depois.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox.registrar precisa rodar dentro de uma transacao");
        }
        jdbc.update(
                """
                INSERT INTO outbox (id, topico, chave, tipo, payload, criado_em)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                """,
                UUID.randomUUID(),
                topico,
                evento.pedidoId().toString(),
                LeitorDeEventos.tipoDe(evento),
                leitor.paraJson(evento),
                Timestamp.from(Instant.now()));
    }
}
