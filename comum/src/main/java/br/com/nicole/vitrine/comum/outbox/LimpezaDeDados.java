package br.com.nicole.vitrine.comum.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Apaga o que ja cumpriu sua funcao. Sem isto, o outbox (com o e-mail do
 * cliente nos eventos finais) e o registro de idempotencia cresceriam para
 * sempre: mais disco, consultas mais lentas e dado pessoal guardado sem motivo
 * (LGPD, art. 15: o tratamento termina quando a finalidade e alcancada).
 */
public class LimpezaDeDados {

    private static final Logger log = LoggerFactory.getLogger(LimpezaDeDados.class);

    private final JdbcTemplate jdbc;
    private final int diasOutbox;
    private final int diasIdempotencia;

    /**
     * @param diasOutbox       eventos ja publicados ficam este tempo, para
     *                         investigacao; os nao publicados nunca saem.
     * @param diasIdempotencia precisa ser maior que a retencao dos topicos do
     *                         Kafka: enquanto uma mensagem puder ser entregue de
     *                         novo, o registro de que ela ja foi processada fica.
     */
    public LimpezaDeDados(JdbcTemplate jdbc, int diasOutbox, int diasIdempotencia) {
        this.jdbc = jdbc;
        this.diasOutbox = diasOutbox;
        this.diasIdempotencia = diasIdempotencia;
    }

    // De madrugada, uma vez por dia. Com varias replicas, todas rodam: o DELETE
    // e idempotente, e a segunda nao acha nada para apagar.
    @Scheduled(cron = "${vitrine.retencao.horario:0 30 3 * * *}")
    public void limpar() {
        int outbox = jdbc.update(
                "DELETE FROM outbox WHERE publicado_em < now() - make_interval(days => ?)", diasOutbox);
        int idempotencia = jdbc.update(
                "DELETE FROM evento_processado WHERE processado_em < now() - make_interval(days => ?)",
                diasIdempotencia);
        log.info("Limpeza: {} evento(s) do outbox e {} registro(s) de idempotencia apagados", outbox, idempotencia);
    }
}
