package br.com.nicole.vitrine.comum.eventos;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Converte evento em JSON e de volta, usando o cabecalho de tipo. */
public class LeitorDeEventos {

    // A lista sai da propria interface sealed: um evento novo entra aqui sozinho.
    private static final Map<String, Class<? extends Evento>> TIPOS = Arrays.stream(Evento.class.getPermittedSubclasses())
            .map(c -> c.asSubclass(Evento.class))
            .collect(Collectors.toUnmodifiableMap(Class::getSimpleName, Function.identity()));

    private final JsonMapper json;

    public LeitorDeEventos(JsonMapper json) {
        this.json = json;
    }

    public static String tipoDe(Evento evento) {
        return evento.getClass().getSimpleName();
    }

    public String paraJson(Evento evento) {
        return json.writeValueAsString(evento);
    }

    /** Mensagem recebida do Kafka, ja com id e evento lidos. */
    public record Mensagem(UUID eventoId, Evento evento) {}

    public Mensagem ler(ConsumerRecord<String, String> registro) {
        String tipo = cabecalho(registro, Cabecalhos.TIPO);
        Class<? extends Evento> classe = TIPOS.get(tipo);
        if (classe == null) {
            throw new EventoInvalidoException("Tipo de evento desconhecido: " + tipo);
        }
        try {
            UUID id = UUID.fromString(cabecalho(registro, Cabecalhos.ID));
            return new Mensagem(id, json.readValue(registro.value(), classe));
        } catch (JacksonException | IllegalArgumentException e) {
            throw new EventoInvalidoException("Evento " + tipo + " mal formado", e);
        }
    }

    private static String cabecalho(ConsumerRecord<String, String> registro, String nome) {
        Header header = registro.headers().lastHeader(nome);
        if (header == null) {
            throw new EventoInvalidoException("Mensagem sem o cabecalho " + nome);
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
