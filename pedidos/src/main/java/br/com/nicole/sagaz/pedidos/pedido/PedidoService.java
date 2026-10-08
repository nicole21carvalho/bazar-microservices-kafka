package br.com.nicole.sagaz.pedidos.pedido;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.nicole.sagaz.comum.eventos.Evento.ItemPedido;
import br.com.nicole.sagaz.comum.eventos.Evento.PedidoCancelado;
import br.com.nicole.sagaz.comum.eventos.Evento.PedidoConfirmado;
import br.com.nicole.sagaz.comum.eventos.Evento.PedidoCriado;
import br.com.nicole.sagaz.comum.eventos.Topicos;
import br.com.nicole.sagaz.comum.outbox.Outbox;
import br.com.nicole.sagaz.pedidos.cliente.Cliente;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class PedidoService {

    private static final Logger log = LoggerFactory.getLogger(PedidoService.class);

    private final PedidoRepository pedidos;
    private final Outbox outbox;
    private final MeterRegistry metricas;

    public PedidoService(PedidoRepository pedidos, Outbox outbox, MeterRegistry metricas) {
        this.pedidos = pedidos;
        this.outbox = outbox;
        this.metricas = metricas;
    }

    /**
     * Grava o pedido e o evento na mesma transacao (outbox). A resposta sai
     * antes de estoque e pagamento: o pedido nasce PENDENTE e o cliente
     * acompanha o status.
     */
    @Transactional
    public Pedido criar(Cliente cliente, List<Pedido.Item> itensPedidos) {
        // O mesmo produto duas vezes na lista vira uma linha so, com a soma.
        Map<String, Integer> somados = new LinkedHashMap<>();
        itensPedidos.forEach(i -> somados.merge(i.produtoId(), i.quantidade(), Integer::sum));
        List<Pedido.Item> itens = somados.entrySet().stream()
                .map(e -> new Pedido.Item(e.getKey(), e.getValue()))
                .toList();

        Pedido pedido = pedidos.save(new Pedido(cliente.id(), cliente.email(), itens));
        outbox.registrar(Topicos.PEDIDOS, new PedidoCriado(
                pedido.getId(),
                itens.stream().map(i -> new ItemPedido(i.produtoId(), i.quantidade())).toList(),
                pedido.getCriadoEm()));
        log.info("Pedido {} criado com {} item(ns)", pedido.getId(), itens.size());
        return pedido;
    }

    @Transactional
    public void estoqueReservado(UUID pedidoId, BigDecimal total) {
        comPedido(pedidoId, p -> p.estoqueReservado(total));
    }

    @Transactional
    public void pagamentoAprovado(UUID pedidoId, BigDecimal valor) {
        comPedido(pedidoId, p -> {
            if (p.confirmar(valor)) {
                outbox.registrar(Topicos.PEDIDOS, new PedidoConfirmado(p.getId(), p.getClienteEmail(), valor));
                registrarDesfecho(p);
                log.info("Pedido {} confirmado", p.getId());
            }
        });
    }

    /** Estoque ou pagamento recusou: o pedido termina sem venda. */
    @Transactional
    public void recusado(UUID pedidoId, String motivo) {
        comPedido(pedidoId, p -> {
            if (p.cancelar(motivo)) {
                // O estoque escuta este evento e devolve os itens, se tinha
                // reservado (compensacao). As notificacoes avisam o cliente.
                outbox.registrar(Topicos.PEDIDOS, new PedidoCancelado(p.getId(), p.getClienteEmail(), motivo));
                registrarDesfecho(p);
                log.info("Pedido {} cancelado: {}", p.getId(), motivo);
            }
        });
    }

    /**
     * Quanto tempo o cliente esperou, do pedido ao desfecho, passando por
     * estoque e pagamento. E o numero que importa para quem compra: a latencia
     * de cada servico isolado nao mostra a espera somada.
     */
    private void registrarDesfecho(Pedido p) {
        Timer.builder("sagaz.saga.duracao")
                .description("Tempo do pedido criado ate confirmado ou cancelado")
                .tag("status", p.getStatus().name())
                .publishPercentileHistogram()
                .register(metricas)
                .record(Duration.between(p.getCriadoEm(), Instant.now()));
    }

    private void comPedido(UUID pedidoId, Consumer<Pedido> acao) {
        pedidos.findById(pedidoId).ifPresentOrElse(acao,
                () -> log.warn("Evento para pedido inexistente {}: ignorado", pedidoId));
    }
}
