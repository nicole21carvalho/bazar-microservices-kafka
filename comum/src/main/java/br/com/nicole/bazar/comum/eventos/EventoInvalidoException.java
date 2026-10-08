package br.com.nicole.bazar.comum.eventos;

/**
 * Mensagem que nunca vai dar certo, por mais que se tente de novo (tipo
 * desconhecido, JSON quebrado). Vai direto para o Dead Letter Topic, sem as
 * novas tentativas que valem para falhas passageiras.
 */
public class EventoInvalidoException extends RuntimeException {

    public EventoInvalidoException(String mensagem) {
        super(mensagem);
    }

    public EventoInvalidoException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
