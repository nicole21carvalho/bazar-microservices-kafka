package br.com.nicole.bazar.pedidos.pedido;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.nicole.bazar.comum.eventos.Evento.ItemPedido;
import br.com.nicole.bazar.comum.eventos.Evento.PedidoCancelado;
import br.com.nicole.bazar.comum.eventos.Evento.PedidoConfirmado;
import br.com.nicole.bazar.comum.eventos.Evento.PedidoCriado;
import br.com.nicole.bazar.comum.eventos.Topicos;
import br.com.nicole.bazar.comum.outbox.Outbox;
import br.com.nicole.bazar.pedidos.cliente.Cliente;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class PedidoService {

    private static final Logger log = LoggerFactory.getLogger(PedidoService.class);

    /** O pedido devolvido, e se ele ja existia (a mesma chave de idempotencia de novo). */
    public record Criacao(Pedido pedido, boolean repetido) {}

    /** A chave de idempotencia ja foi usada por este cliente num pedido com outros itens. */
    public static class ChaveReutilizada extends RuntimeException {
        public ChaveReutilizada() {
            super("Idempotency-Key ja usada em um pedido com outros itens");
        }
    }

    private final PedidoRepository pedidos;
    private final Outbox outbox;
    private final MeterRegistry metricas;
    private final JdbcTemplate jdbc;

    public PedidoService(PedidoRepository pedidos, Outbox outbox, MeterRegistry metricas, JdbcTemplate jdbc) {
        this.pedidos = pedidos;
        this.outbox = outbox;
        this.metricas = metricas;
        this.jdbc = jdbc;
    }

    /**
     * Grava o pedido e o evento na mesma transacao (outbox). A resposta sai
     * antes de estoque e pagamento: o pedido nasce PENDENTE e o cliente
     * acompanha o status.
     *
     * Com chave de idempotencia, repetir a chamada (depois de um timeout, por
     * exemplo) devolve o pedido ja criado em vez de criar outro.
     */
    @Transactional
    public Criacao criar(Cliente cliente, List<Pedido.Item> itensPedidos, String chave) {
        // O mesmo produto duas vezes na lista vira uma linha so, com a soma.
        Map<String, Integer> somados = new LinkedHashMap<>();
        itensPedidos.forEach(i -> somados.merge(i.produtoId(), i.quantidade(), Integer::sum));
        List<Pedido.Item> itens = somados.entrySet().stream()
                .map(e -> new Pedido.Item(e.getKey(), e.getValue()))
                .toList();

        UUID id = UUID.randomUUID();
        if (chave != null) {
            Optional<Pedido> anterior = reservarChave(cliente, chave, impressao(itens), id);
            if (anterior.isPresent()) {
                log.info("Pedido {} devolvido de novo pela chave de idempotencia", anterior.get().getId());
                return new Criacao(anterior.get(), true);
            }
        }

        Pedido pedido = pedidos.save(new Pedido(id, cliente.id(), cliente.email(), itens));
        outbox.registrar(Topicos.PEDIDOS, new PedidoCriado(
                pedido.getId(),
                itens.stream().map(i -> new ItemPedido(i.produtoId(), i.quantidade())).toList(),
                pedido.getCriadoEm()));
        log.info("Pedido {} criado com {} item(ns)", pedido.getId(), itens.size());
        return new Criacao(pedido, false);
    }

    /**
     * Grava a chave apontando para o pedido que vai ser criado, ou devolve o
     * pedido que ja tem essa chave. A chave primaria decide, e nao um SELECT
     * antes: com duas requisicoes iguais ao mesmo tempo, a segunda espera a
     * primeira terminar no INSERT e depois encontra o pedido dela.
     */
    private Optional<Pedido> reservarChave(Cliente cliente, String chave, String impressao, UUID pedidoId) {
        int inseridas = jdbc.update(
                """
                INSERT INTO pedido_chave (cliente_id, chave, impressao, pedido_id, criado_em)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT DO NOTHING
                """,
                cliente.id(), chave, impressao, pedidoId);
        if (inseridas == 1) {
            return Optional.empty();
        }
        var registro = jdbc.queryForMap(
                "SELECT impressao, pedido_id FROM pedido_chave WHERE cliente_id = ? AND chave = ?",
                cliente.id(), chave);
        if (!impressao.equals(registro.get("impressao"))) {
            throw new ChaveReutilizada();
        }
        UUID existente = (UUID) registro.get("pedido_id");
        // A chave estrangeira garante o pedido; se faltasse, criar outro seria o erro que a chave evita.
        return Optional.of(pedidos.findById(existente)
                .orElseThrow(() -> new IllegalStateException("Chave aponta para pedido inexistente " + existente)));
    }

    /** Os itens em ordem de produto: a mesma compra da sempre o mesmo texto. */
    private static String impressao(List<Pedido.Item> itens) {
        return itens.stream()
                .sorted(Comparator.comparing(Pedido.Item::produtoId))
                .map(i -> i.produtoId() + "=" + i.quantidade())
                .collect(Collectors.joining(";"));
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
        Timer.builder("bazar.saga.duracao")
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
