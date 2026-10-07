package br.com.nicole.vitrine.estoque.reserva;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservaRepository extends JpaRepository<Reserva, UUID> {}
