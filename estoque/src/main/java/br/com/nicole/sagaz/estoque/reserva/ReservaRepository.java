package br.com.nicole.sagaz.estoque.reserva;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservaRepository extends JpaRepository<Reserva, UUID> {}
