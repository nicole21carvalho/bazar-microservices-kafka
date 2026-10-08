package br.com.nicole.sagaz.estoque.reserva;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import br.com.nicole.sagaz.comum.consumo.ConsumoIdempotente;
import br.com.nicole.sagaz.comum.eventos.Evento.PedidoCancelado;
import br.com.nicole.sagaz.comum.eventos.Evento.PedidoConfirmado;
import br.com.nicole.sagaz.comum.eventos.Evento.PedidoCriado;
import br.com.nicole.sagaz.comum.eventos.LeitorDeEventos;
import br.com.nicole.sagaz.comum.eventos.Topicos;

@Component
public class ConsumidorDePedidos {

    private static final String CONSUMIDOR = "estoque";

    private final LeitorDeEventos leitor;
    private final ConsumoIdempotente idempotente;
    private final ReservaService reservas;

    public ConsumidorDePedidos(LeitorDeEventos leitor, ConsumoIdempotente idempotente, ReservaService reservas) {
        this.leitor = leitor;
        this.idempotente = idempotente;
        this.reservas = reservas;
    }

    // Uma transacao so para a marca de idempotencia e o efeito do evento: se o
    // efeito falhar, a marca volta junto e o evento pode ser tentado de novo.
    @Transactional
    @KafkaListener(topics = Topicos.PEDIDOS, groupId = CONSUMIDOR)
    public void receber(ConsumerRecord<String, String> registro) {
        var mensagem = leitor.ler(registro);
        boolean interessa = mensagem.evento() instanceof PedidoCriado
                || mensagem.evento() instanceof PedidoCancelado
                || mensagem.evento() instanceof PedidoConfirmado;
        if (!interessa || !idempotente.primeiraVez(mensagem.eventoId(), CONSUMIDOR)) {
            return;
        }
        switch (mensagem.evento()) {
            case PedidoCriado e -> reservas.reservar(e);
            case PedidoCancelado e -> reservas.liberar(e);
            case PedidoConfirmado e -> reservas.baixar(e);
            default -> throw new IllegalStateException("inalcancavel");
        }
    }
}
