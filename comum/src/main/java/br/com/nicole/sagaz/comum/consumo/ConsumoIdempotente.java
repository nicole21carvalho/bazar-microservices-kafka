package br.com.nicole.sagaz.comum.consumo;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * O Kafka e o outbox entregam "pelo menos uma vez": a mesma mensagem pode
 * chegar duas vezes. Este registro garante que o efeito acontece uma vez so
 * (o pagamento nao e cobrado em dobro, o estoque nao baixa duas vezes).
 */
public class ConsumoIdempotente {

    private final JdbcTemplate jdbc;

    public ConsumoIdempotente(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Marca o evento como processado e diz se e a primeira vez.
     *
     * Precisa rodar na mesma transacao do efeito do evento: se o efeito der
     * rollback, a marca tambem volta, e o evento pode ser processado de novo.
     * A chave primaria decide, e nao um SELECT antes: duas copias da mensagem
     * chegando juntas passariam as duas por um SELECT.
     */
    public boolean primeiraVez(UUID eventoId, String consumidor) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("ConsumoIdempotente precisa rodar dentro de uma transacao");
        }
        int inseridas = jdbc.update(
                """
                INSERT INTO evento_processado (evento_id, consumidor, processado_em)
                VALUES (?, ?, now())
                ON CONFLICT DO NOTHING
                """,
                eventoId,
                consumidor);
        return inseridas == 1;
    }
}
