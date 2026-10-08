package br.com.nicole.sagaz.pagamentos.pagamento;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.nicole.sagaz.comum.eventos.Evento.EstoqueReservado;
import br.com.nicole.sagaz.comum.eventos.Evento.PagamentoAprovado;
import br.com.nicole.sagaz.comum.eventos.Evento.PagamentoRecusado;
import br.com.nicole.sagaz.comum.eventos.Topicos;
import br.com.nicole.sagaz.comum.outbox.Outbox;

/**
 * Gateway de pagamento simulado: aprova ate um limite e recusa acima dele. Num
 * sistema real, aqui entraria a chamada a um provedor (Stripe, Pagar.me...),
 * com a chave de idempotencia do provedor sendo o pedidoId.
 */
@Service
public class PagamentoService {

    private static final Logger log = LoggerFactory.getLogger(PagamentoService.class);

    private final PagamentoRepository pagamentos;
    private final Outbox outbox;
    private final BigDecimal limite;

    public PagamentoService(
            PagamentoRepository pagamentos,
            Outbox outbox,
            @Value("${sagaz.pagamentos.limite-aprovacao:1000.00}") BigDecimal limite) {
        this.pagamentos = pagamentos;
        this.outbox = outbox;
        this.limite = limite;
    }

    @Transactional
    public void cobrar(EstoqueReservado reserva) {
        if (pagamentos.existsByPedidoId(reserva.pedidoId())) {
            return; // ja cobrado: nunca cobrar duas vezes
        }
        if (reserva.total().compareTo(limite) > 0) {
            String motivo = "Valor acima do limite do cartão (" + reais(limite) + ")";
            pagamentos.save(Pagamento.recusado(reserva.pedidoId(), reserva.total(), motivo));
            outbox.registrar(Topicos.PAGAMENTOS, new PagamentoRecusado(reserva.pedidoId(), motivo));
            log.info("Pedido {}: pagamento de {} recusado", reserva.pedidoId(), reserva.total());
            return;
        }
        Pagamento pagamento = pagamentos.save(Pagamento.aprovado(reserva.pedidoId(), reserva.total()));
        outbox.registrar(Topicos.PAGAMENTOS,
                new PagamentoAprovado(reserva.pedidoId(), pagamento.getId(), reserva.total()));
        log.info("Pedido {}: pagamento de {} aprovado", reserva.pedidoId(), reserva.total());
    }

    private static String reais(BigDecimal valor) {
        return NumberFormat.getCurrencyInstance(Locale.of("pt", "BR")).format(valor);
    }
}
