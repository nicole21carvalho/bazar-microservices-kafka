package br.com.nicole.sagaz.estoque.produto;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Produto {

    @Id
    private String id;

    private String nome;
    private BigDecimal preco;
    private int disponivel;

    protected Produto() {}

    public Produto(String id, String nome, BigDecimal preco, int disponivel) {
        this.id = id;
        this.nome = nome;
        this.preco = preco;
        this.disponivel = disponivel;
    }

    public boolean tem(int quantidade) {
        return disponivel >= quantidade;
    }

    public void separar(int quantidade) {
        if (!tem(quantidade)) {
            throw new IllegalStateException("Estoque insuficiente de " + id);
        }
        disponivel -= quantidade;
    }

    public void devolver(int quantidade) {
        disponivel += quantidade;
    }

    public String getId() { return id; }
    public String getNome() { return nome; }
    public BigDecimal getPreco() { return preco; }
    public int getDisponivel() { return disponivel; }
}
