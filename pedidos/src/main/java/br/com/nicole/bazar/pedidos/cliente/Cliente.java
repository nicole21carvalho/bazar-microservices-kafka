package br.com.nicole.bazar.pedidos.cliente;

/** Quem esta fazendo a requisicao. As rotas recebem este record, e nao o token. */
public record Cliente(String id, String email) {}
