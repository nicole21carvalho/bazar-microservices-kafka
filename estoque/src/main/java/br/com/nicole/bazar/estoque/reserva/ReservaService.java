package br.com.nicole.bazar.estoque.reserva;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.nicole.bazar.comum.eventos.Evento.EstoqueRecusado;
import br.com.nicole.bazar.comum.eventos.Evento.EstoqueReservado;
import br.com.nicole.bazar.comum.eventos.Evento.ItemPedido;
import br.com.nicole.bazar.comum.eventos.Evento.PedidoCancelado;
import br.com.nicole.bazar.comum.eventos.Evento.PedidoConfirmado;
import br.com.nicole.bazar.comum.eventos.Evento.PedidoCriado;
import br.com.nicole.bazar.comum.eventos.Topicos;
import br.com.nicole.bazar.comum.outbox.Outbox;
import br.com.nicole.bazar.estoque.produto.Produto;
import br.com.nicole.bazar.estoque.produto.ProdutoRepository;

@Service
public class ReservaService {

    private static final Logger log = LoggerFactory.getLogger(ReservaService.class);

    private final ProdutoRepository produtos;
    private final ReservaRepository reservas;
    private final Outbox outbox;

    public ReservaService(ProdutoRepository produtos, ReservaRepository reservas, Outbox outbox) {
        this.produtos = produtos;
        this.reservas = reservas;
        this.outbox = outbox;
    }

    /** Separa todos os itens, ou nenhum: um pedido nao sai com metade dos produtos. */
    @Transactional
    public void reservar(PedidoCriado pedido) {
        if (reservas.existsById(pedido.pedidoId())) {
            return; // ja tratado (evento repetido com outro id, por seguranca)
        }
        Map<String, Integer> pedidas = pedido.itens().stream()
                .collect(Collectors.toMap(ItemPedido::produtoId, ItemPedido::quantidade, Integer::sum));
        Map<String, Produto> travados = produtos.travarParaReserva(pedidas.keySet()).stream()
                .collect(Collectors.toMap(Produto::getId, Function.identity()));

        for (var item : pedidas.entrySet()) {
            Produto produto = travados.get(item.getKey());
            if (produto == null) {
                recusar(pedido, "Produto inexistente: " + item.getKey());
                return;
            }
            if (!produto.tem(item.getValue())) {
                recusar(pedido, "Sem estoque de " + produto.getNome() + ": pedido " + item.getValue()
                        + ", disponível " + produto.getDisponivel());
                return;
            }
        }

        BigDecimal total = BigDecimal.ZERO;
        for (var item : pedidas.entrySet()) {
            Produto produto = travados.get(item.getKey());
            produto.separar(item.getValue());
            total = total.add(produto.getPreco().multiply(BigDecimal.valueOf(item.getValue())));
        }
        List<Reserva.Item> itens = pedidas.entrySet().stream()
                .map(e -> new Reserva.Item(e.getKey(), e.getValue()))
                .toList();
        reservas.save(new Reserva(pedido.pedidoId(), itens, total));
        outbox.registrar(Topicos.ESTOQUE, new EstoqueReservado(pedido.pedidoId(), total));
        log.info("Pedido {}: itens reservados, total {}", pedido.pedidoId(), total);
    }

    private void recusar(PedidoCriado pedido, String motivo) {
        outbox.registrar(Topicos.ESTOQUE, new EstoqueRecusado(pedido.pedidoId(), motivo));
        log.info("Pedido {}: reserva recusada ({})", pedido.pedidoId(), motivo);
    }

    /** Compensacao da saga: o pagamento falhou, os itens voltam para a prateleira. */
    @Transactional
    public void liberar(PedidoCancelado cancelado) {
        reservas.findById(cancelado.pedidoId()).ifPresent(reserva -> {
            if (!reserva.liberar()) return;
            var ids = reserva.getItens().stream().map(Reserva.Item::produtoId).toList();
            Map<String, Produto> travados = produtos.travarParaReserva(ids).stream()
                    .collect(Collectors.toMap(Produto::getId, Function.identity()));
            reserva.getItens().forEach(i -> travados.get(i.produtoId()).devolver(i.quantidade()));
            log.info("Pedido {}: itens devolvidos ao estoque", cancelado.pedidoId());
        });
    }

    @Transactional
    public void baixar(PedidoConfirmado confirmado) {
        reservas.findById(confirmado.pedidoId()).ifPresent(Reserva::baixar);
    }
}
