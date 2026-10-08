package br.com.nicole.bazar.pagamentos.pagamento;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import br.com.nicole.bazar.comum.consumo.ConsumoIdempotente;
import br.com.nicole.bazar.comum.eventos.Evento.EstoqueReservado;
import br.com.nicole.bazar.comum.eventos.LeitorDeEventos;
import br.com.nicole.bazar.comum.eventos.Topicos;

/** So cobra depois que o estoque separou os itens: nunca cobra por algo que nao tem. */
@Component
public class ConsumidorDeReservas {

    private static final String CONSUMIDOR = "pagamentos";

    private final LeitorDeEventos leitor;
    private final ConsumoIdempotente idempotente;
    private final PagamentoService pagamentos;

    public ConsumidorDeReservas(LeitorDeEventos leitor, ConsumoIdempotente idempotente, PagamentoService pagamentos) {
        this.leitor = leitor;
        this.idempotente = idempotente;
        this.pagamentos = pagamentos;
    }

    @Transactional
    @KafkaListener(topics = Topicos.ESTOQUE, groupId = CONSUMIDOR)
    public void receber(ConsumerRecord<String, String> registro) {
        var mensagem = leitor.ler(registro);
        if (mensagem.evento() instanceof EstoqueReservado reserva
                && idempotente.primeiraVez(mensagem.eventoId(), CONSUMIDOR)) {
            pagamentos.cobrar(reserva);
        }
    }
}
