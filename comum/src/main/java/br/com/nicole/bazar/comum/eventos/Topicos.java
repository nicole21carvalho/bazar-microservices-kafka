package br.com.nicole.bazar.comum.eventos;

/**
 * Um topico por servico que publica. Quem consome escolhe os tipos que
 * interessam pelo cabecalho {@link Cabecalhos#TIPO}, sem precisar desserializar
 * a mensagem para descobrir o que ela e.
 */
public final class Topicos {

    public static final String PEDIDOS = "bazar.pedidos";
    public static final String ESTOQUE = "bazar.estoque";
    public static final String PAGAMENTOS = "bazar.pagamentos";

    private Topicos() {}
}
