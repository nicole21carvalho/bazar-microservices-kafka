package br.com.nicole.vitrine.estoque.reserva;

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

/** Itens separados para um pedido, enquanto a saga nao termina. */
@Entity
public class Reserva {

    public enum Status { RESERVADA, BAIXADA, LIBERADA }

    @Embeddable
    public record Item(String produtoId, int quantidade) {}

    @Id
    private UUID pedidoId;

    @Enumerated(EnumType.STRING)
    private Status status;

    private BigDecimal total;
    private Instant criadaEm;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "reserva_item", joinColumns = @JoinColumn(name = "pedido_id"))
    private List<Item> itens = new ArrayList<>();

    protected Reserva() {}

    public Reserva(UUID pedidoId, List<Item> itens, BigDecimal total) {
        this.pedidoId = pedidoId;
        this.itens = new ArrayList<>(itens);
        this.total = total;
        this.status = Status.RESERVADA;
        this.criadaEm = Instant.now();
    }

    /** Venda concluida: os itens saem do estoque de vez. */
    public void baixar() {
        if (status == Status.RESERVADA) status = Status.BAIXADA;
    }

    /** Compensacao: devolve se ainda estava so reservada. Diz se devolveu. */
    public boolean liberar() {
        if (status != Status.RESERVADA) return false;
        status = Status.LIBERADA;
        return true;
    }

    public UUID getPedidoId() { return pedidoId; }
    public Status getStatus() { return status; }
    public BigDecimal getTotal() { return total; }
    public List<Item> getItens() { return itens; }
}
