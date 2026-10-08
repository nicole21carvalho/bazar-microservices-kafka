package br.com.nicole.sagaz.estoque.produto;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/produtos")
public class ProdutoController {

    public record ProdutoSaida(String id, String nome, BigDecimal preco, int disponivel) {}

    private final ProdutoRepository produtos;

    public ProdutoController(ProdutoRepository produtos) {
        this.produtos = produtos;
    }

    @GetMapping
    public List<ProdutoSaida> listar() {
        return produtos.findAllByOrderByNome().stream()
                .map(p -> new ProdutoSaida(p.getId(), p.getNome(), p.getPreco(), p.getDisponivel()))
                .toList();
    }
}
