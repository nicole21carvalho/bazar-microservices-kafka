package br.com.nicole.bazar.pedidos.pedido;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Version;

/**
 * Estado do pedido ao longo da saga:
 *
 * <pre>
 * PENDENTE --EstoqueReservado--> AGUARDANDO_PAGAMENTO --PagamentoAprovado--> CONFIRMADO
 *    |                                  |
 *    +--EstoqueRecusado--> CANCELADO <--+--PagamentoRecusado
 * </pre>
 *
 * Os eventos de estoque e de pagamento chegam por topicos diferentes, entao a
 * ordem entre eles nao e garantida: o PagamentoAprovado pode chegar antes do
 * EstoqueReservado. Por isso confirmar() aceita PENDENTE, e um EstoqueReservado
 * atrasado nao tira o pedido de CONFIRMADO.
 */
@Entity
public class Pedido {

    public enum Status { PENDENTE, AGUARDANDO_PAGAMENTO, CONFIRMADO, CANCELADO }

    @Embeddable
    public record Item(String produtoId, int quantidade) {}

    @Id
    private UUID id;

    private String clienteId;
    private String clienteEmail;

    @Enumerated(EnumType.STRING)
    private Status status;

    private BigDecimal total;
    private String motivo;
    private Instant criadoEm;
    private Instant atualizadoEm;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "pedido_item", joinColumns = @JoinColumn(name = "pedido_id"))
    private List<Item> itens = new ArrayList<>();

    // Trava otimista: duas atualizacoes do mesmo pedido ao mesmo tempo (um
    // evento de estoque e um de pagamento) nao se sobrescrevem; a segunda falha
    // e o Kafka entrega o evento de novo.
    @Version
    private long versao;

    protected Pedido() {}

    public Pedido(String clienteId, String clienteEmail, List<Item> itens) {
        this(UUID.randomUUID(), clienteId, clienteEmail, itens);
    }

    /** Com o id escolhido antes: a chave de idempotencia aponta para ele. */
    public Pedido(UUID id, String clienteId, String clienteEmail, List<Item> itens) {
        this.id = id;
        this.clienteId = clienteId;
        this.clienteEmail = clienteEmail;
        this.itens = new ArrayList<>(itens);
        this.status = Status.PENDENTE;
        this.criadoEm = Instant.now();
        this.atualizadoEm = criadoEm;
    }

    /** Diz se mudou de estado. */
    public boolean estoqueReservado(BigDecimal total) {
        if (status != Status.PENDENTE) return false;
        this.total = total;
        return mudarPara(Status.AGUARDANDO_PAGAMENTO);
    }

    public boolean confirmar(BigDecimal valorPago) {
        if (status != Status.PENDENTE && status != Status.AGUARDANDO_PAGAMENTO) return false;
        this.total = valorPago;
        return mudarPara(Status.CONFIRMADO);
    }

    public boolean cancelar(String motivo) {
        if (status != Status.PENDENTE && status != Status.AGUARDANDO_PAGAMENTO) return false;
        this.motivo = motivo;
        return mudarPara(Status.CANCELADO);
    }

    private boolean mudarPara(Status novo) {
        status = novo;
        atualizadoEm = Instant.now();
        return true;
    }

    public UUID getId() { return id; }
    public String getClienteId() { return clienteId; }
    public String getClienteEmail() { return clienteEmail; }
    public Status getStatus() { return status; }
    public BigDecimal getTotal() { return total; }
    public String getMotivo() { return motivo; }
    public Instant getCriadoEm() { return criadoEm; }
    public Instant getAtualizadoEm() { return atualizadoEm; }
    public List<Item> getItens() { return itens; }
}
