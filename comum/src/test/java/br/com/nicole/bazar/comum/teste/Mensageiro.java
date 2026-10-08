package br.com.nicole.bazar.comum.teste;

import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import br.com.nicole.bazar.comum.eventos.Cabecalhos;
import br.com.nicole.bazar.comum.eventos.Evento;
import br.com.nicole.bazar.comum.eventos.LeitorDeEventos;

/** Faz o papel dos outros servicos nos testes: publica eventos e le o que saiu. */
public class Mensageiro {

    private final KafkaTemplate<String, String> kafka;
    private final ConsumerFactory<String, String> consumidores;
    private final LeitorDeEventos leitor;

    public Mensageiro(
            KafkaTemplate<String, String> kafka, ConsumerFactory<String, String> consumidores, LeitorDeEventos leitor) {
        this.kafka = kafka;
        this.consumidores = consumidores;
        this.leitor = leitor;
    }

    public void publicar(String topico, Evento evento, UUID eventoId) throws Exception {
        publicarCru(topico, evento.pedidoId().toString(), LeitorDeEventos.tipoDe(evento), eventoId, leitor.paraJson(evento));
    }

    public void publicarCru(String topico, String chave, String tipo, UUID eventoId, String corpo) throws Exception {
        var registro = new ProducerRecord<>(topico, chave, corpo);
        registro.headers().add(Cabecalhos.TIPO, tipo.getBytes(StandardCharsets.UTF_8));
        registro.headers().add(Cabecalhos.ID, eventoId.toString().getBytes(StandardCharsets.UTF_8));
        kafka.send(registro).get(10, TimeUnit.SECONDS);
    }

    /** Espera aparecerem no topico as mensagens com esta chave e devolve todas. */
    public List<ConsumerRecord<String, String>> esperar(String topico, String chave, int quantas) {
        var achadas = new ArrayList<ConsumerRecord<String, String>>();
        try (Consumer<String, String> consumidor = consumidores.createConsumer("teste-" + UUID.randomUUID(), "teste")) {
            consumidor.subscribe(List.of(topico));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumidor.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (chave.equals(r.key())) achadas.add(r);
                });
                return achadas.size() >= quantas;
            });
            // Mais um pouco para pegar uma eventual mensagem a mais (duplicada).
            consumidor.poll(Duration.ofSeconds(1)).forEach(r -> {
                if (chave.equals(r.key())) achadas.add(r);
            });
        }
        return achadas;
    }

    public List<Evento> eventos(String topico, UUID pedidoId, int quantos) {
        return esperar(topico, pedidoId.toString(), quantos).stream().map(r -> leitor.ler(r).evento()).toList();
    }
}
