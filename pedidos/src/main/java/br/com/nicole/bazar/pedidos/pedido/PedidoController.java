package br.com.nicole.bazar.pedidos.pedido;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import br.com.nicole.bazar.pedidos.cliente.Cliente;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/pedidos")
public class PedidoController {

    public record ItemEntrada(
            @Pattern(regexp = "[a-z0-9-]{1,60}", message = "produto invalido") String produtoId,
            @Min(1) @Max(99) int quantidade) {}

    public record NovoPedido(@NotEmpty @Size(max = 20) List<@Valid ItemEntrada> itens) {}

    public record PedidoSaida(
            UUID id,
            Pedido.Status status,
            BigDecimal total,
            String motivo,
            List<Pedido.Item> itens,
            Instant criadoEm,
            Instant atualizadoEm) {

        static PedidoSaida de(Pedido p) {
            return new PedidoSaida(
                    p.getId(), p.getStatus(), p.getTotal(), p.getMotivo(), List.copyOf(p.getItens()),
                    p.getCriadoEm(), p.getAtualizadoEm());
        }
    }

    // UUID cabe aqui, e tambem qualquer chave aleatoria longa o bastante.
    private static final java.util.regex.Pattern CHAVE_VALIDA = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{8,100}");

    private final PedidoService servico;
    private final PedidoRepository pedidos;

    public PedidoController(PedidoService servico, PedidoRepository pedidos) {
        this.servico = servico;
        this.pedidos = pedidos;
    }

    /**
     * 202 Accepted, e nao 201: o pedido foi recebido, mas estoque e pagamento
     * ainda vao decidir. O cliente acompanha pelo endereco do Location.
     *
     * O cabecalho Idempotency-Key (opcional) torna a chamada segura de repetir:
     * a mesma chave devolve o mesmo pedido, com "Idempotent-Replayed: true".
     */
    @PostMapping
    public ResponseEntity<PedidoSaida> criar(
            @Valid @RequestBody NovoPedido entrada,
            Cliente cliente,
            @RequestHeader(name = "Idempotency-Key", required = false) String chave) {
        if (chave != null && !CHAVE_VALIDA.matcher(chave).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key invalida: use de 8 a 100 letras, numeros, '-' ou '_'");
        }
        var itens = entrada.itens().stream().map(i -> new Pedido.Item(i.produtoId(), i.quantidade())).toList();
        PedidoService.Criacao criacao;
        try {
            criacao = servico.criar(cliente, itens, chave);
        } catch (PedidoService.ChaveReutilizada e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
        Pedido pedido = criacao.pedido();
        var resposta = ResponseEntity.accepted().location(URI.create("/api/pedidos/" + pedido.getId()));
        if (criacao.repetido()) {
            resposta.header("Idempotent-Replayed", "true");
        }
        return resposta.body(PedidoSaida.de(pedido));
    }

    @GetMapping("/{id}")
    public PedidoSaida detalhar(@PathVariable UUID id, Cliente cliente) {
        // 404 tambem para pedido de outro cliente: 403 confirmaria que o id existe.
        return pedidos.findByIdAndClienteId(id, cliente.id())
                .map(PedidoSaida::de)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado"));
    }

    @GetMapping
    public List<PedidoSaida> listar(Cliente cliente) {
        return pedidos.findTop20ByClienteIdOrderByCriadoEmDesc(cliente.id()).stream().map(PedidoSaida::de).toList();
    }
}
