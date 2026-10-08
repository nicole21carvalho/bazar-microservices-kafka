package br.com.nicole.sagaz.pedidos.cliente;

/** Quem esta fazendo a requisicao. As rotas recebem este record, e nao o token. */
public record Cliente(String id, String email) {}
