package br.com.nicole.sagaz.pedidos.pedido;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PedidoRepository extends JpaRepository<Pedido, UUID> {

    Optional<Pedido> findByIdAndClienteId(UUID id, String clienteId);

    List<Pedido> findTop20ByClienteIdOrderByCriadoEmDesc(String clienteId);
}
