package br.com.nicole.sagaz.comum.eventos;

/** Cabecalhos que acompanham toda mensagem publicada pelo outbox. */
public final class Cabecalhos {

    /** Id unico do evento: o consumidor usa para ignorar uma mensagem repetida. */
    public static final String ID = "evento-id";

    /** Nome do record (ex.: "PedidoCriado"), para saber como ler o corpo. */
    public static final String TIPO = "evento-tipo";

    private Cabecalhos() {}
}
