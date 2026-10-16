package br.com.nicole.bazar.pedidos.pedido;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Apaga as chaves de idempotencia vencidas. A chave so serve para repetir uma
 * chamada que ficou sem resposta, coisa de segundos ou minutos; guardar por
 * mais tempo so ocuparia disco (e guardaria o id do cliente sem motivo).
 */
@Component
public class LimpezaDeChaves {

    private static final Logger log = LoggerFactory.getLogger(LimpezaDeChaves.class);

    private final JdbcTemplate jdbc;
    private final int horas;

    public LimpezaDeChaves(JdbcTemplate jdbc, @Value("${bazar.retencao.chaves-horas:24}") int horas) {
        this.jdbc = jdbc;
        this.horas = horas;
    }

    @Scheduled(cron = "${bazar.retencao.horario:0 30 3 * * *}")
    public void limpar() {
        int apagadas = jdbc.update(
                "DELETE FROM pedido_chave WHERE criado_em < now() - make_interval(hours => ?)", horas);
        log.info("Limpeza: {} chave(s) de idempotencia apagada(s)", apagadas);
    }
}
