package br.com.nicole.sagaz.pagamentos.pagamento;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;

@Entity
public class Pagamento {

    public enum Status { APROVADO, RECUSADO }

    @Id
    private UUID id;

    // UNIQUE no banco: um pedido nunca tem dois pagamentos, mesmo que o evento
    // chegue duas vezes com ids diferentes.
    private UUID pedidoId;

    private BigDecimal valor;

    @Enumerated(EnumType.STRING)
    private Status status;

    private String motivo;
    private Instant criadoEm;

    protected Pagamento() {}

    private Pagamento(UUID pedidoId, BigDecimal valor, Status status, String motivo) {
        this.id = UUID.randomUUID();
        this.pedidoId = pedidoId;
        this.valor = valor;
        this.status = status;
        this.motivo = motivo;
        this.criadoEm = Instant.now();
    }

    public static Pagamento aprovado(UUID pedidoId, BigDecimal valor) {
        return new Pagamento(pedidoId, valor, Status.APROVADO, null);
    }

    public static Pagamento recusado(UUID pedidoId, BigDecimal valor, String motivo) {
        return new Pagamento(pedidoId, valor, Status.RECUSADO, motivo);
    }

    public UUID getId() { return id; }
    public UUID getPedidoId() { return pedidoId; }
    public BigDecimal getValor() { return valor; }
    public Status getStatus() { return status; }
    public String getMotivo() { return motivo; }
}
