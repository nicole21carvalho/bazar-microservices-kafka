package br.com.nicole.vitrine.estoque.produto;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface ProdutoRepository extends JpaRepository<Produto, String> {

    /**
     * SELECT ... FOR UPDATE: trava as linhas ate o fim da transacao. Dois pedidos
     * do ultimo cafe disponivel chegando juntos: o segundo espera o primeiro
     * terminar e ja ve o estoque zerado, em vez de os dois venderem o mesmo item.
     *
     * ORDER BY id: todo mundo trava na mesma ordem. Sem isso, o pedido A trava o
     * cafe e espera o cha, enquanto o B trava o cha e espera o cafe (deadlock).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Produto p WHERE p.id IN :ids ORDER BY p.id")
    List<Produto> travarParaReserva(Collection<String> ids);

    List<Produto> findAllByOrderByNome();
}
