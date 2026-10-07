package br.com.nicole.vitrine.pagamentos.pagamento;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PagamentoRepository extends JpaRepository<Pagamento, UUID> {

    boolean existsByPedidoId(UUID pedidoId);
}
