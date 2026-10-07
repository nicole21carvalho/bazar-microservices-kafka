package br.com.nicole.vitrine.comum.eventos;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Todos os eventos da loja. Sealed: o compilador conhece a lista inteira, e um
 * switch sobre Evento que esquecer um tipo nao compila.
 *
 * Cada evento carrega o pedidoId, que tambem e a chave da mensagem no Kafka:
 * mensagens com a mesma chave caem na mesma particao e chegam na ordem em que
 * foram enviadas, entao os eventos de um pedido nunca chegam embaralhados.
 */
public sealed interface Evento {

    UUID pedidoId();

    record ItemPedido(String produtoId, int quantidade) {}

    /**
     * pedidos -> estoque: um cliente fez um pedido. Sem e-mail nem id do
     * cliente: o estoque so precisa dos itens. Dado pessoal que nao vai para o
     * Kafka nao vaza pelo Kafka, nem fica guardado nos topicos (LGPD, art. 6, III).
     */
    record PedidoCriado(UUID pedidoId, List<ItemPedido> itens, Instant criadoEm) implements Evento {}

    /** estoque -> pedidos e pagamentos: os itens foram separados; o total ja tem os precos. */
    record EstoqueReservado(UUID pedidoId, BigDecimal total) implements Evento {}

    /** estoque -> pedidos: faltou algum item. */
    record EstoqueRecusado(UUID pedidoId, String motivo) implements Evento {}

    /** pagamentos -> pedidos. */
    record PagamentoAprovado(UUID pedidoId, UUID pagamentoId, BigDecimal valor) implements Evento {}

    /** pagamentos -> pedidos. */
    record PagamentoRecusado(UUID pedidoId, String motivo) implements Evento {}

    /** pedidos -> estoque e notificacoes: fim feliz da saga. */
    record PedidoConfirmado(UUID pedidoId, String clienteEmail, BigDecimal total) implements Evento {}

    /**
     * pedidos -> estoque e notificacoes: a saga terminou sem venda. O estoque
     * devolve os itens se tinha reservado (compensacao).
     */
    record PedidoCancelado(UUID pedidoId, String clienteEmail, String motivo) implements Evento {}
}
