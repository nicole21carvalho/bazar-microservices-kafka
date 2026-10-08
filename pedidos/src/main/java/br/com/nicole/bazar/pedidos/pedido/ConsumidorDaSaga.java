package br.com.nicole.bazar.pedidos.pedido;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import br.com.nicole.bazar.comum.consumo.ConsumoIdempotente;
import br.com.nicole.bazar.comum.eventos.Evento.EstoqueRecusado;
import br.com.nicole.bazar.comum.eventos.Evento.EstoqueReservado;
import br.com.nicole.bazar.comum.eventos.Evento.PagamentoAprovado;
import br.com.nicole.bazar.comum.eventos.Evento.PagamentoRecusado;
import br.com.nicole.bazar.comum.eventos.LeitorDeEventos;
import br.com.nicole.bazar.comum.eventos.Topicos;

/** Acompanha a saga: cada resposta de estoque e pagamento move o pedido. */
@Component
public class ConsumidorDaSaga {

    private static final String CONSUMIDOR = "pedidos";

    private final LeitorDeEventos leitor;
    private final ConsumoIdempotente idempotente;
    private final PedidoService pedidos;

    public ConsumidorDaSaga(LeitorDeEventos leitor, ConsumoIdempotente idempotente, PedidoService pedidos) {
        this.leitor = leitor;
        this.idempotente = idempotente;
        this.pedidos = pedidos;
    }

    @Transactional
    @KafkaListener(topics = {Topicos.ESTOQUE, Topicos.PAGAMENTOS}, groupId = CONSUMIDOR)
    public void receber(ConsumerRecord<String, String> registro) {
        var mensagem = leitor.ler(registro);
        boolean interessa = mensagem.evento() instanceof EstoqueReservado
                || mensagem.evento() instanceof EstoqueRecusado
                || mensagem.evento() instanceof PagamentoAprovado
                || mensagem.evento() instanceof PagamentoRecusado;
        if (!interessa || !idempotente.primeiraVez(mensagem.eventoId(), CONSUMIDOR)) {
            return;
        }
        switch (mensagem.evento()) {
            case EstoqueReservado e -> pedidos.estoqueReservado(e.pedidoId(), e.total());
            case EstoqueRecusado e -> pedidos.recusado(e.pedidoId(), e.motivo());
            case PagamentoAprovado e -> pedidos.pagamentoAprovado(e.pedidoId(), e.valor());
            case PagamentoRecusado e -> pedidos.recusado(e.pedidoId(), e.motivo());
            default -> throw new IllegalStateException("inalcancavel");
        }
    }
}
