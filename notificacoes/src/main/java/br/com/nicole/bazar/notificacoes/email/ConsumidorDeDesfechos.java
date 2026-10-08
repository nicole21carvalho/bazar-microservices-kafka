package br.com.nicole.bazar.notificacoes.email;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import br.com.nicole.bazar.comum.consumo.ConsumoIdempotente;
import br.com.nicole.bazar.comum.eventos.Evento.PedidoCancelado;
import br.com.nicole.bazar.comum.eventos.Evento.PedidoConfirmado;
import br.com.nicole.bazar.comum.eventos.LeitorDeEventos;
import br.com.nicole.bazar.comum.eventos.Topicos;

/** Avisa o cliente quando a saga termina, com venda ou sem. */
@Component
public class ConsumidorDeDesfechos {

    private static final String CONSUMIDOR = "notificacoes";

    private final LeitorDeEventos leitor;
    private final ConsumoIdempotente idempotente;
    private final AvisoPorEmail avisos;

    public ConsumidorDeDesfechos(LeitorDeEventos leitor, ConsumoIdempotente idempotente, AvisoPorEmail avisos) {
        this.leitor = leitor;
        this.idempotente = idempotente;
        this.avisos = avisos;
    }

    @Transactional
    @KafkaListener(topics = Topicos.PEDIDOS, groupId = CONSUMIDOR)
    public void receber(ConsumerRecord<String, String> registro) {
        var mensagem = leitor.ler(registro);
        boolean interessa = mensagem.evento() instanceof PedidoConfirmado
                || mensagem.evento() instanceof PedidoCancelado;
        if (!interessa || !idempotente.primeiraVez(mensagem.eventoId(), CONSUMIDOR)) {
            return;
        }
        switch (mensagem.evento()) {
            case PedidoConfirmado e -> avisos.confirmado(e);
            case PedidoCancelado e -> avisos.cancelado(e);
            default -> throw new IllegalStateException("inalcancavel");
        }
    }
}
